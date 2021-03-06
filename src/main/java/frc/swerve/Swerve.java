package frc.swerve;

import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.wpilibj.Notifier;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;

import frc.robot.Robot;
import frc.util.GRTUtil;
import frc.gen.BIGData;
import frc.gen.Config;

public class Swerve implements Runnable {

	private final double SWERVE_WIDTH;
	private final double SWERVE_HEIGHT;
	private final double RADIUS;
	private final double WHEEL_ANGLE;
	private final double ROTATE_SCALE;
	/** proportional scaling constant */
	private final double kP;
	/** derivative scaling constant */
	private final double kD;

	private Notifier notifier;

	private NetworkTableEntry gyroAngle;
	private NavXGyro gyro;
	/** wheels[0]=fr, wheels[1]=br, wheels[2]=bl, wheels[3]=fl */
	private Wheel[] wheels;

	/** requested x velocity, y velocity, angular velocity(rad/s), and angle */
	private volatile double userVX, userVY, userW, angle;
	/** determines if robot centric control or field centric control is used */
	private volatile boolean robotCentric;
	private volatile boolean withPID;
	private volatile SwerveData swerveData;

	public Swerve() {
		gyroAngle = NetworkTableInstance.getDefault().getTable("PositionTracking").getEntry("angle");
		this.gyro = Robot.GYRO;
		gyro.reset();
		angle = 0.0;
		robotCentric = false;
		withPID = false;
		wheels = new Wheel[4];
		wheels[0] = new Wheel("fr");
		wheels[1] = new Wheel("br");
		wheels[2] = new Wheel("bl");
		wheels[3] = new Wheel("fl");

		SWERVE_WIDTH = BIGData.getDouble("swerve_width");
		SWERVE_HEIGHT = BIGData.getDouble("swerve_height");
		kP = BIGData.getDouble("swerve_kp");
		kD = BIGData.getDouble("swerve_kd");
		RADIUS = Math.sqrt(SWERVE_WIDTH * SWERVE_WIDTH + SWERVE_HEIGHT * SWERVE_HEIGHT) / 2;
		WHEEL_ANGLE = Math.atan2(SWERVE_WIDTH, SWERVE_HEIGHT);
		ROTATE_SCALE = 1 / RADIUS;
		calcSwerveData();
		notifier = new Notifier(this);
		notifier.startPeriodic(0.02);
		// setAngle(0.0); TODO uncomment this
	}

	public void run() {
		double w = userW;
		if (withPID) {
			w = calcPID();
		}
		refreshVals();
		changeMotors(userVX, userVY, w);
		calcSwerveData();
		SmartDashboard.putNumber("Angle", gyro.getAngle());
		gyroAngle.setDouble(Math.toRadians(gyro.getAngle()));
	}

	private void refreshVals(){
		userVX = BIGData.getDouble("drive_x");
		userVY = BIGData.getDouble("drive_y");
		userW = BIGData.getDouble("drive_w");
		if (userW != 0) {
			withPID = false;
		}
	}

	/**
	 * calculates angle correction for robot based on current angle, requested
	 * angle, kP, and kD
	 */
	private double calcPID() {
		double error = GRTUtil.distanceToAngle(Math.toRadians(gyro.getAngle()), angle);
		double w = error * kP - Math.toRadians(gyro.getRate()) * kD;
		return w;
	}

	/**
	 * sets the angle of the robot
	 * 
	 * @param angle
	 *                  the angle to turn the robot to, in radians
	 */
	public void setAngle(double angle) {
		withPID = true;
		this.angle = angle;
	}

	/** sets whether we use robot centric or field centric control */
	public void setRobotCentric(boolean mode) {
		robotCentric = mode;
	}

	/**
	 * change the motors to reach the requested values
	 * 
	 * @param vx
	 *               the requested x velocity from -1.0 to 1.0
	 * @param vy
	 *               the requested y velocity from -1.0 to 1.0
	 * @param w
	 *               the requested angular velocity
	 */
	public void changeMotors(double vx, double vy, double w) {
		w *= ROTATE_SCALE;
		double gyroAngle = (robotCentric ? 0 : Math.toRadians(gyro.getAngle()));
		for (int i = 0; i < wheels.length; i++) {
			// angle in radians
			double wheelAngle = getRelativeWheelAngle(i) + gyroAngle;
			double dx = RADIUS * Math.cos(wheelAngle);
			double dy = RADIUS * Math.sin(wheelAngle);
			double wheelVX = vx - dy * w;
			double wheelVY = vy + dx * w;
			double wheelPos = Math.atan2(wheelVY, wheelVX) - gyroAngle;
			// System.out.println("wheel: " + i + " wheelPos: " + wheelPos + " dx: " + dx +
			// " dy: " + dy);
			double power = Math.sqrt(wheelVX * wheelVX + wheelVY * wheelVY);
			wheels[i].set(wheelPos, power);
		}
	}

	public SwerveData getSwerveData() {
		return swerveData;
	}

	private void calcSwerveData() {
		double gyroAngle = Math.toRadians(gyro.getAngle());
		double gyroRate = Math.toRadians(gyro.getRate());
		double vx = 0;
		double vy = 0;
		double w = 0;
		for (int i = 0; i < wheels.length; i++) {
			double wheelAngle = getRelativeWheelAngle(i);
			double wheelPos = wheels[i].getCurrentPosition();
			double speed = wheels[i].getDriveSpeed();
			w += Math.sin(wheelPos - wheelAngle) * speed / RADIUS;
			wheelPos += gyroAngle;
			vx += Math.cos(wheelPos) * speed;
			vy += Math.sin(wheelPos) * speed;
		}
		w /= 4.0;
		vx /= 4.0;
		vy /= 4.0;
		swerveData = new SwerveData(gyroAngle, gyroRate, vx, vy, w);
	}

	private double getRelativeWheelAngle(int i) {
		double angle = WHEEL_ANGLE;
		if (i == 0 || i == 2) {
			angle *= -1;
		}
		if (i == 0 || i == 3) {
			angle += Math.PI;
		}
		return angle;
	}

	/**
	 * Takes the current position of the wheels and sets them as zero in the
	 * currently running program and adds them to the Basic tab on SmartDashboard
	 */
	public void zeroRotate() {
		for (int i = 0; i < wheels.length; i++) {
			wheels[i].zero();
			BIGData.put(wheels[i].getName() + "_offset", wheels[i].getOffset());
			SmartDashboard.putString("DB/String " + i, wheels[i].getName() + "_offset: " + wheels[i].getOffset());
		}
		Config.updateConfigFile();
	}

}
