package org.usfirst.frc.team4183.robot.subsystems.DriveSubsystem;

import com.ctre.phoenix.motion.TrajectoryPoint;
import com.ctre.phoenix.motorcontrol.ControlMode;
import com.ctre.phoenix.motorcontrol.FeedbackDevice;
import com.ctre.phoenix.motorcontrol.NeutralMode;
import com.ctre.phoenix.motorcontrol.can.WPI_TalonSRX;

import edu.wpi.first.wpilibj.Notifier;
import edu.wpi.first.wpilibj.SpeedController;
import edu.wpi.first.wpilibj.Timer;
import edu.wpi.first.wpilibj.drive.DifferentialDrive;
import edu.wpi.first.wpilibj.smartdashboard.SendableChooser;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;

import java.util.ArrayList;

import org.usfirst.frc.team4183.robot.Robot;
import org.usfirst.frc.team4183.robot.RobotMap;
import org.usfirst.frc.team4183.utils.Deadzone;
import org.usfirst.frc.team4183.robot.subsystems.BitBucketsSubsystem;

import org.usfirst.frc.team4183.robot.subsystems.SubsystemUtilities.DiagnosticsState;
import org.usfirst.frc.team4183.robot.subsystems.SubsystemUtilities.SubsystemTelemetryState;

import jaci.pathfinder.Pathfinder;
import jaci.pathfinder.Trajectory;
import jaci.pathfinder.Waypoint;
import jaci.pathfinder.modifiers.TankModifier;

public class DriveSubsystem extends BitBucketsSubsystem
{
	private final double INCH_PER_WHEEL_ROT = RobotMap.INCH_PER_WHEEL_ROT;
	

	// Can adjust these to help the robot drive straight with zero turn stick.
	// +Values will add +yaw correct (CCW viewed from top) when going forward.
	private final double YAW_CORRECT_VELOCITY = 0.0;  // Multiplied by inch/sec so value will be small!
	private final double YAW_CORRECT_ACCEL = 0.0;
	
	private final double LOW_SENS_GAIN = 0.6;		
	private final double ALIGN_LOOP_GAIN = 0.04;

	// The counts-per-rev is printed on the encoder -
	// it's the 1st number after the "E4P" or "E4T"
	private final int ENCODER_PULSES_PER_REV = 250; 
	private final boolean REVERSE_SENSOR = false;  
	private final int EDGES_PER_ENCODER_COUNT = 4;
	private double yawSetPoint;
		
	private final WPI_TalonSRX leftFrontMotor;		
	private final WPI_TalonSRX leftRearMotor;		// Use follower mode

	private final WPI_TalonSRX rightFrontMotor;
	private final WPI_TalonSRX rightRearMotor;		// Use follower mode

	private final DifferentialDrive drive;
	
	private static SendableChooser<SubsystemTelemetryState> telemetryState;
	
	Waypoint[] points = new Waypoint[]
			{
				new Waypoint(0, 0, 0),
				new Waypoint(1, 2, Pathfinder.d2r(45)),
                new Waypoint(3, 4, 0),
                new Waypoint(5,6, Pathfinder.d2r(45))
			};
	
    public DriveSubsystem()
    {
    		setName("DriveSubsystem");
    	
    		
    		DIAG_LOOPS_RUN = 10;
    		
	    	leftFrontMotor = new WPI_TalonSRX(RobotMap.LEFT_DRIVE_MOTOR_FRONT_ID);
	    	leftRearMotor = new WPI_TalonSRX(RobotMap.LEFT_DRIVE_MOTOR_REAR_ID);
	    		    	
	    	// Use follower mode to minimize shearing commands that could occur if
	    	// separate commands are sent to each motor in a group
	    	leftRearMotor.set(ControlMode.Follower, leftFrontMotor.getDeviceID());

	    	leftFrontMotor.setSafetyEnabled(false);
	    	leftRearMotor.setSafetyEnabled(false);
	    	
	    	rightFrontMotor  = new WPI_TalonSRX(RobotMap.RIGHT_DRIVE_MOTOR_FRONT_ID);
	    	rightRearMotor   = new WPI_TalonSRX(RobotMap.RIGHT_DRIVE_MOTOR_REAR_ID);
	
	    	// Use follower mode to minimize shearing commands that could occur if
	    	// separate commands are sent to each motor in a group
	    	rightRearMotor.set(ControlMode.Follower, rightFrontMotor.getDeviceID());
	    	
	    	rightFrontMotor.setSafetyEnabled(false);
	    	rightRearMotor.setSafetyEnabled(false);
	
	    	// The differential drive simply requires a left and right speed controller
	    	// In this case we can use a single motor controller type on each side
	    	// rather than a SpeedControllerGroup because we set up the follower mode
	    	// NOTE: We will want to test that this really works, but it should prevent
	    	// the loop in the SpeedControllerGroup from being sheared by preemption on
	    	// a side that could cause the motors to have different commands on rapid
	    	// reverse boundaries (which is hard on the gears, even for 20 ms).
	    	// The left vs right can still be sheared by preemption but that is generally 
	    	// less harmful on the entire robot as forces and slippage will be absorbed
	    	// through the tires and frame (not JUST the gearbox)
	    	drive = new DifferentialDrive(leftFrontMotor, rightFrontMotor);
    	
	    	// Now get the other modes set up
	    	setNeutral(NeutralMode.Brake);
	    	telemetryState = new SendableChooser<SubsystemTelemetryState>();
	    	
	    	telemetryState.addDefault("Off", SubsystemTelemetryState.OFF);
	    	telemetryState.addObject( "On",  SubsystemTelemetryState.ON);
	    	
	    	SmartDashboard.putData("DriveTelemetry", telemetryState);
    }
    
    // The following interior class allows us to drive the profile for each step.
    // If we are driving multiple provides then we will need to hit all of them
    // and the followers; there is a risk of shearing the calls in time without
    // strict real-time controls that Java and non-RTOS environment simply can't
    // do.
    // CTRE recommends that the runnable (thread) runs at least 2x faster than
    // the delta-t for the steps in the profile; we believe that this is so there 
    // is time to handle being late on any one wake up. It should be noted however, 
    // that any really long-lead preemption that prevents this runnable thread 
    // from executing on time could cause the profile to not execute and would delay
    // moving the profile along. In other words: IF the profile buffering has NOT
    // already been loaded and requires this thread to push it through, the only
    // advantage that this technique offers over purely pushing the velocity and
    // check on position is that the controller can do that check at 1000 Hz where
    // the software must also read a status frame before it can assess position.
    class forestgump implements java.lang.Runnable
    {
    	public void run()
    	{
    		leftFrontMotor.processMotionProfileBuffer();
    		
    		// Since we have two motors on the gear box, we will
    		// keep commanding the other one to follow whatever
    		// the master is doing
    		leftRearMotor.set(ControlMode.Follower, leftFrontMotor.getDeviceID());
    	}
    }
    
    // This is the instance containing the runnable so it can be started in the background
    Notifier _notifier=new Notifier(new forestgump());
    
    // A simple test of motion profiles that takes a waypoint array formatted
    // for Jaci's library and converts it into a tank drive profile
    // A waypoint is a position and heading to be at during some path (i.e., connect the dots and face a specific direction)
    public void MotionControlTest(Waypoint[] waypoints)
    {
    	 // First, configure the trajectory container to describe the
    	 // constraints to apply when generating the trajectory
    	 // Cubic fits will use 3 waypoints to generate a curve equations, quintic will use 5 waypoints
    	 // High samples produce a smoother trajectory but will take longer to compute
    	 // Delta Time determines how many steps to break the trajectory into (too small may be difficult to process)
    	 // The speed, acceleration, and "jerk" define the limits to stay within
    	 Trajectory.Config config = new Trajectory.Config(Trajectory.FitMethod.HERMITE_CUBIC, // type of curve fit
										    			  Trajectory.Config.SAMPLES_HIGH,     // dense samples for smooth (slower calculation)
										    			  0.05,	// Delta Time between steps (seconds)
										    			  1.7, 	// Maximum speed along center line (m/s)
										    			  2.0,  // Maximum acceleration along center line (m/s^2)
										    			  60.0); // Maximum "jerk" along center line (m/s^3)
    	 
    	 // Convert the waypoints into a trajectory
    	 // A trajectory is just an array of timed segments containing the
    	 // position and speed data at each delta time along the trajectory
    	 // There are many elements within the segment that can be used to display or
    	 // verify compliance with the original waypoints, during execution, or
    	 // even check total distance and time.
    	 Trajectory trajectory = Pathfinder.generate(waypoints, config);
    	 
    	 System.out.printf("The Trajectory is %d points long\n", trajectory.length());

    	 // Just be safe and make sure that the trajectory has at least one point
    	 if (trajectory.length() > 0)
    	 {
	         // The above calculation is assumed to be on the centerline of whatever
	    	 // In our case we have a tank drive for which the left and right motors
	    	 // must be driven different to actually make the turns
	    	 // The TankModifier simply must know how far apart the wheels are (left to right)
	         TankModifier modifier = new TankModifier(trajectory).modify(RobotMap.ROBOT_WHEEL_TRACK_INCHES*0.0254);
	
	         // Extract the left and right trajectory modifications
	         // Once we have those we will be able to extract the segment
	         // data and pass it to the motor controller
	         Trajectory left = modifier.getLeftTrajectory();
	         Trajectory right = modifier.getRightTrajectory();
	         
	         // Both the left and right should also have non-zero size
	         // Otherwise, what is the point? (sorry)
	         System.out.printf("The Left  Trajectory is %d points long\n", left.length());
	         System.out.printf("The Right Trajectory is %d points long\n", right.length());
	         
	         if ((left.length() > 0) &&
	             (right.length() > 0))
	         {
	        	 // Sizing makes a little sense, so we will continue
	        	 
		         // An underrun is when we ask the motion profile to process and
		         // there are no more profile points to consume. This happens if
		         // we fail to push enough or forget to tell the controller where
		         // the end of the run is, or ask it to run again after the end.
		         // When this happens the status will set a sticky underrun flag
		         // that we should always clear before starting again to make
		         // it possible to detect this error.
		         //
		         // It is possible that if we don't this the controller may not
		         // run the profile at all; but we would need to check the API
		         // or documentation (LOL) to see if the underrun is an inhibiter.
		         leftFrontMotor.clearMotionProfileHasUnderrun(RobotMap.CONTROLLER_TIMEOUT_MS);
		         
		         // Forget the past, we are starting a new trajectory
		         leftFrontMotor.clearMotionProfileTrajectories();
		         
		         // Also, the motion profile timing appears to be monotonic (one period) at a base
		         // level with the ability to modify timing if needed at each trajectory point
		         // NOTE: For now we will just make it the same time as the Trajectory.Config, but
		         // because the interface takes integer milliseconds we will jam it in for now.
		         // Eventually we really want a constant that we would use for both interfaces.
		         leftFrontMotor.configMotionProfileTrajectoryPeriod( 50, RobotMap.CONTROLLER_TIMEOUT_MS); // MAGIC NUMBER
		         
		         // Jaci's code organizes Trajectory into Segments, which is an apt description
		         // because each segment is the connection between two points and will persist
		         // for some amount of time.
		         // CTRE defines a TrajectoryPoint class that has the same context at Segment
		         // with position, speed, and timing data (among other things)
		         //
		         // BECAUSE the units are different we must convert from Jaci's types to CTRE's types.
		         
		         // We only need a single point that we will modify as we push to the buffer
		         // The timeDur field is only used to modify the trajectory period we defined above.
		         // We will simply use the time we had in the original trajectory configuration
		         // and only change the timing if we believe that the trajectory needs to be stretched
		         // for some reason; there are better ways of doing corrections, however.
		         // The profile slot selections refer to which PIDF constants to use for two different
		         // motion profile modes ("regular" and "ARC" mode)... I have no idea what "ARC" is
		         // because this appears to be a new control mode. However, since we will likely have
		         // a PIDF set in slot 0 for "normal" move forward logic, we will place the constants
		         // needed for motion profiles into slot 1.
		         //
		         // If you remember from the documentation, the motion profile firmware only needs
		         // a feed forward constant based on the ratio of the A/D converter scale to the the
		         // encoder quadrature scale at the percentage commanded when we run the wheels at some
		         // speed. Since each controller MAY be different, the constants may be different, but
		         // we will place them in slot 1 on each controller once we calculate them.
		         //
		         // For this test, we are just trying to drive one controller for the first time and
		         // will deal with multiple controllers after this test.
		         TrajectoryPoint point = new TrajectoryPoint();
		    	 point.timeDur = TrajectoryPoint.TrajectoryDuration.Trajectory_Duration_0ms;
		    	 point.profileSlotSelect0=1;
		    	 point.profileSlotSelect1=1;
		    	 
		    	 // Everything else about the point changes as we go
		    	 
		         for(int i=0; i < left.length(); i++)
		         {
		        	 Trajectory.Segment seg = left.get(i);
		        	 
		        	 point.position = seg.position*2.916; 		// MAGIC conversion from m to rotations
		        	 point.velocity = seg.velocity*174.97;		// MAGIC conversion from m/s to RPM
		        	 
		        	 point.headingDeg = 0.0;					// FUTURE integration with Pigeon IMU?
		
		        	 // In our case, only the first point is the zero position
		        	 // So we will flag it as we pass through
		        	 point.zeroPos=false;
		        	 if(i==0) point.zeroPos = true;
		        	 
		        	 // ...and only the last point is the last point
		        	 point.isLastPoint=false;
		        	 if((i+1)==left.length()) point.isLastPoint=true;
		        	 
		        	 // Now we just load up the buffer
		        	 // NOTE: There are some comments in the documentation
		        	 // that imply the limit is 2048 so if we create trajectories
		        	 // we will have to pay attention so we can feed the
		        	 // beast part-way and then top it off as we go. This is
		        	 // where the underrun can happen if we are not careful.
		        	 // BUT, for now we "know" our test trajectories are short
		        	 // and we will just push... okay we didn't really check
		        	 // here but we did plot it once. We will add checks later.
		        	 leftFrontMotor.pushMotionProfileTrajectory(point);
		         }
		
		         // Now that the trajectory is loaded up there is little more
		         // to do than make it go
		         // First we will command the controller the run a "frame" at 1/2 the period (2x rate)
		         // of the base trajectory period. At first this looks like this
		         // implies the controller will just run on its own but there is
		         // the runnable we just created that appears to be required to
		         // actually move the points into the controller. If this is true
		         // the runnable rate is higher only to make sure that any delays
		         // caused by Java or the OS don't unnecessarily cause the trajectory
		         // to be stretched... i.e., CTRE is not be entirely honest about why
		         // they do this, but it is because the trajectory is not actually
		         // buffered in the controller (physical) but only in the software.
		         //
		         // What this implies is that the underrun can still happen at some
		         // point if the frame and trajectory period come due and there is
		         // no data in the controller... it may mean we will need to monitor
		         // the underrun status
		         
		         leftFrontMotor.changeMotionControlFramePeriod(25);	// tell the controller the frame period (not trajectory period)
		         _notifier.startPeriodic(0.025);					// Start the thread that will actually sequence the frames
	         }
    	 }
    	 // else there is something wrong because the trajectory was too short
    	 // We don't bother complaining since we displayed the size above and
    	 // we can debug that if we need to, later
    }
    
  
    
    private double shapeAxis( double x) {
		x = Deadzone.f( x, .05);
		return Math.signum(x) * (x*x);
	}
    
	
	// +turnStick produces right turn (CW from above, -yaw angle)
	public void arcadeDrive(double fwdStick, double turnStick) {
		
		if(Robot.oi.btnLowSensitiveDrive.get()) {
			fwdStick *= LOW_SENS_GAIN;
			turnStick *= LOW_SENS_GAIN;
		}
		if(Robot.oi.btnInvertAxis.get()) {
			fwdStick *= -1.0;
		}
		
		// Shape axis for human control
		fwdStick = shapeAxis(fwdStick);
		turnStick = shapeAxis(turnStick);
					
		if( fwdStick == 0.0 && turnStick == 0.0) {
			setAllMotorsZero();
		}
		else {
			// Turn stick is + to the right;
			// but arcadeDrive 2nd arg + produces left turn
			// (this is +yaw when yaw is defined according to right-hand-rule
			// with z-axis up, so arguably correct).
			// Anyhow need the - sign on turnStick to make it turn correctly.
			drive.arcadeDrive( fwdStick, turnStick + yawCorrect(), false);
			leftRearMotor.set(ControlMode.Follower, leftFrontMotor.getDeviceID());	// Reinforce
			rightRearMotor.set(ControlMode.Follower, rightFrontMotor.getDeviceID());			

		}
	}
	public void doAutoTurn( double turn) {
		drive.arcadeDrive( 0.0, turn, false);				
	}
	
	public void setAlignDrive(boolean start) {
		if(start) {
			yawSetPoint = Robot.imu.getYawDeg();
		} 
	}
	
	public void doAlignDrive(double fwdStick, double turnStick) {
					
		if(Robot.oi.btnLowSensitiveDrive.get())
			fwdStick *= LOW_SENS_GAIN;
		
		if(Robot.oi.btnInvertAxis.get()) {
			fwdStick *= -1.0;
		}
		
		fwdStick = shapeAxis(fwdStick);
		turnStick = shapeAxis(turnStick);
					
		if( fwdStick == 0.0 && turnStick == 0.0) {
			setAllMotorsZero();
		}
		else {
			
			// Turn stick is + to the right,
			// +yaw is CCW looking down,
			// so + stick should lower the setpoint. 
			yawSetPoint += -0.3 * turnStick;
			
			double error = ALIGN_LOOP_GAIN * (yawSetPoint - Robot.imu.getYawDeg());				
			drive.arcadeDrive( fwdStick, error + yawCorrect(), false);
		}
	}
	
	// Autonomous: drive in straight line
	public void doAutoStraight( double fwd) {
		if( fwd == 0.0)
			setAllMotorsZero();
		else {
			double error = ALIGN_LOOP_GAIN * (yawSetPoint - Robot.imu.getYawDeg());				
			drive.arcadeDrive( fwd, error + yawCorrect(), false);				
		}			
	}
	@Override
	protected void initDefaultCommand() 
	{
		setDefaultCommand(new Idle());		
		
	}

	public void disable() {
		setAllMotorsZero();
	}
	
	// Might need to change from .set(value) to .set(mode, value)
	private void setAllMotorsZero() 
	{
		leftFrontMotor.set(ControlMode.PercentOutput, 0.0);
		leftRearMotor.set(ControlMode.PercentOutput, 0.0);
		rightFrontMotor.set(ControlMode.PercentOutput, 0.0);
		rightRearMotor.set(ControlMode.PercentOutput, 0.0);			
	}
	private void setupClosedLoopMaster( WPI_TalonSRX m) 
	{
		// TODO: New functions provide ErrorCode feedback if there is a problem setting up the controller
		
		m.set(ControlMode.Position, 0.0);
		
		m.configSelectedFeedbackSensor(FeedbackDevice.QuadEncoder, 0, RobotMap.CONTROLLER_TIMEOUT_MS);
		
		// NOTE: The encoder codes per revolution interface no longer exists
		// All of the interfaces operate in native units which are 4x the counts per revolution
		// An encoder that returns 250 counts per rev will be 1000 native units per rev
		// But the resolution is still 360/250 degrees
		// An encoder that return 1024 counts per rev will be 4096 native units per rev
		// But the resolution is still 360/1024 degrees.
		// Basically, we just need to do the math ourselves
		
		//m.setInverted(true);  // TODO: When do we turn this off?
		m.setSelectedSensorPosition(0, 0, RobotMap.CONTROLLER_TIMEOUT_MS);	// Zero the sensor where we are right now
		
		// NOTE: PIDF constants should be determined based on native units
		m.config_kP(0, 0.016, RobotMap.CONTROLLER_TIMEOUT_MS); // May be able to increase gain a bit	
		m.config_kI(0, 0, RobotMap.CONTROLLER_TIMEOUT_MS);
		m.config_kD(0, 0, RobotMap.CONTROLLER_TIMEOUT_MS); 
		m.config_kF(0, 0, RobotMap.CONTROLLER_TIMEOUT_MS);
		m.config_IntegralZone(0, 0, RobotMap.CONTROLLER_TIMEOUT_MS);
		
		m.configClosedloopRamp(0.250, RobotMap.CONTROLLER_TIMEOUT_MS); // Smoothes things a bit: Don't switch from neutral to full too quickly
		
		// TODO: Need to understand the implication of this error limit
		// If it is in "ticks" or "pulse" or whatever, then how big are 8 ticks
		// E.g., if encoder is 256 steps per revolution then 8/256 is 11.25 degress, which is actually
		// quite large. So we need to figure this out if we want to have real control.
		m.configAllowableClosedloopError(0, 0, RobotMap.CONTROLLER_TIMEOUT_MS);  // Specified in native "ticks"?
		
		m.configPeakOutputForward(1.0, RobotMap.CONTROLLER_TIMEOUT_MS);
		m.configPeakOutputReverse(-1.0, RobotMap.CONTROLLER_TIMEOUT_MS);
		m.configNominalOutputForward(1.0/3.0, RobotMap.CONTROLLER_TIMEOUT_MS);
		m.configNominalOutputReverse(-1.0/3.0, RobotMap.CONTROLLER_TIMEOUT_MS);
					
	}
	
	public void doLockDrive(double value) 
	{
		leftFrontMotor.set(ControlMode.Position, value);
		leftRearMotor.set(ControlMode.Follower, leftFrontMotor.getDeviceID());	// Reinforce
		rightFrontMotor.set(ControlMode.Position, value);
		rightRearMotor.set(ControlMode.Follower, rightFrontMotor.getDeviceID());			
	}
	public void setLockDrive( boolean start) 
	{

		if( start) 
		{
			setupClosedLoopMaster(leftFrontMotor);
			setupClosedLoopMaster(rightFrontMotor);

			leftRearMotor.set(ControlMode.Follower, leftFrontMotor.getDeviceID());	// Reinforce
			leftRearMotor.setInverted(false); // Follow the front
			rightRearMotor.set(ControlMode.Follower, rightFrontMotor.getDeviceID());			
			rightRearMotor.setInverted(false); // Follow the front
		}
		else 
		{
			leftFrontMotor.set(ControlMode.PercentOutput,0.0);
			leftRearMotor.set(ControlMode.PercentOutput,0.0);
			rightFrontMotor.set(ControlMode.PercentOutput,0.0);
			rightRearMotor.set(ControlMode.PercentOutput,0.0);							
		}
	}
	

	/** 
	 * setNeutral is a pass through interface to each motor in the subsystem
	 * 
	 * @param neutralMode is either Coast or Brake. Braking will apply force to come to a stop at zero input
	 */
	private void setNeutral(NeutralMode neutralMode) 
	{	
		leftFrontMotor.setNeutralMode(neutralMode);
		leftRearMotor.setNeutralMode(neutralMode);
		rightFrontMotor.setNeutralMode(neutralMode);
		rightRearMotor.setNeutralMode(neutralMode);
		
	}
	private double yawCorrect() {
		return YAW_CORRECT_VELOCITY * getFwdVelocity_ips() 
				+ YAW_CORRECT_ACCEL * getFwdCurrent();
	}
	public double getRightPosition_inch() {
		// Right motor encoder reads -position when going forward!
		// TODO: This is wrong! Need new constants
		return -INCH_PER_WHEEL_ROT * rightFrontMotor.getSelectedSensorPosition(RobotMap.PRIMARY_PID_LOOP);						
	}
	
	private int getMotorNativeUnits(WPI_TalonSRX m) {
		return m.getSelectedSensorPosition(RobotMap.PRIMARY_PID_LOOP);
	}
	
	public int getRightNativeUnits() {
		return getMotorNativeUnits(rightFrontMotor);
	}
	
	public int getLeftNativeUnits() {
		return getMotorNativeUnits(leftFrontMotor);
	}
	
	private double getMotorEncoderUnits(WPI_TalonSRX m) {
		return getMotorNativeUnits(m)/EDGES_PER_ENCODER_COUNT;
	}
	
	public double getRightEncoderUnits() {
		return getMotorEncoderUnits(rightFrontMotor);
	}
	
	public double getLeftEncoderUnits() {
		return getMotorEncoderUnits(leftFrontMotor);
	}
	
	private ControlMode getMotorMode(WPI_TalonSRX m) {
		return m.getControlMode();
	}
	
	public ControlMode getRightFrontMode() {
		return getMotorMode(rightFrontMotor);
	}
	
	public ControlMode getLeftFrontMode() {
		return getMotorMode(leftFrontMotor);
	}
	
	public ControlMode getLeftRearMode() {
		return getMotorMode(leftRearMotor);
	}
	
	public ControlMode getRightRearMode() {
		return getMotorMode(rightRearMotor);
	}
	
	public double inchesToEncoderTicks(double inches) {
		return ENCODER_PULSES_PER_REV * EDGES_PER_ENCODER_COUNT * inches / RobotMap.WHEEL_CIRCUMFERENCE;
	}

	public double getFwdVelocity_ips() {
		// Right side motor reads -velocity when going forward!
		double fwdSpeedRpm = (leftFrontMotor.getSelectedSensorVelocity(RobotMap.PRIMARY_PID_LOOP) - rightFrontMotor.getSelectedSensorVelocity(RobotMap.PRIMARY_PID_LOOP))/2.0;
		return (INCH_PER_WHEEL_ROT / 60.0) * fwdSpeedRpm;
	}
	public double getFwdCurrent() {
		// OutputCurrent always positive so apply sign of drive voltage to get real answer.
		// Also, right side has -drive when going forward!
		double leftFront = leftFrontMotor.getOutputCurrent() * Math.signum( leftFrontMotor.getMotorOutputVoltage());
		double leftRear = leftRearMotor.getOutputCurrent() * Math.signum( leftRearMotor.getMotorOutputVoltage());
		double rightFront = -rightFrontMotor.getOutputCurrent() * Math.signum( rightFrontMotor.getMotorOutputVoltage());
		double rightRear = -rightRearMotor.getOutputCurrent() * Math.signum( rightRearMotor.getMotorOutputVoltage());
		return (leftFront + leftRear + rightFront + rightRear)/4.0;
	}
	
	public double getPosition_inch() {
		// TODO Auto-generated method stub
		return 0;
	}
	
	private void setupPositionControl(WPI_TalonSRX m) {
		m.setSelectedSensorPosition(0, 0, RobotMap.CONTROLLER_TIMEOUT_MS);
		m.config_kP(0, SmartDashboard.getNumber("Kp", 0.016), RobotMap.CONTROLLER_TIMEOUT_MS); // May be able to increase gain a bit	
		m.config_kI(0, SmartDashboard.getNumber("Ki", 0.016), RobotMap.CONTROLLER_TIMEOUT_MS);
		m.config_kD(0, SmartDashboard.getNumber("Kd", 0.016), RobotMap.CONTROLLER_TIMEOUT_MS);
		
		m.set(ControlMode.PercentOutput, 0.0);
	}
	
	public void setupPositionControl() {
		setupPositionControl(leftFrontMotor);
		setupPositionControl(leftRearMotor);
		setupPositionControl(rightFrontMotor);
		setupPositionControl(rightRearMotor);
	}
	
	private void setPos(WPI_TalonSRX m, double value) {
		
		m.set(ControlMode.Position, value);
	}
	
	public void setPos(double value) {
		setPos(leftFrontMotor, inchesToEncoderTicks(value));
		setPos(rightFrontMotor, -inchesToEncoderTicks(value));
		setPos(leftRearMotor, inchesToEncoderTicks(value));
		setPos(rightRearMotor, -inchesToEncoderTicks(value));
	}
	
	/* Any hardware devices used in this subsystem must
	*  have a check here to see if it is still connected and 
	*  working properly. For motors check for current draw.
	*  Return true iff all devices are working properly. Otherwise
	*  return false. This sets all motors to percent output
	*/
	@Override
	public void diagnosticsInit() {
		
	}
	
	@Override
	public void diagnosticsExecute() {

		/* Init Diagnostics */
		SmartDashboard.putBoolean("RunningDiag", true);
		
		rightFrontMotor.set(ControlMode.PercentOutput, RobotMap.MOTOR_TEST_PERCENT);
		rightRearMotor.set(ControlMode.PercentOutput, -RobotMap.MOTOR_TEST_PERCENT);
		leftFrontMotor.set(ControlMode.PercentOutput, -RobotMap.MOTOR_TEST_PERCENT);
		leftRearMotor.set(ControlMode.PercentOutput, RobotMap.MOTOR_TEST_PERCENT);
	}
	
	@Override
	public void diagnosticsCheck() {
		/* Reset flag */
		runDiagnostics = false;
		
		/* Diagnostics */
		lastKnownState = DiagnosticsState.PASS;
		SmartDashboard.putBoolean(getName() + "Diagnostics", true); // All good until we find a fault
		
		SmartDashboard.putBoolean("DiagnosticsFR", true);
		if(rightFrontMotor.getOutputCurrent() <= RobotMap.MINUMUM_MOTOR_CURR) {
			SmartDashboard.putBoolean("DiagnosticsFR", false);
			SmartDashboard.putBoolean(getName() + "Diagnostics", false);
			lastKnownState = DiagnosticsState.FAIL;
		}
		rightFrontMotor.set(ControlMode.PercentOutput, 0.0);
		
		SmartDashboard.putBoolean("DiagnosticsBR", true);
		if(rightRearMotor.getOutputCurrent() <= RobotMap.MINUMUM_MOTOR_CURR) {
			SmartDashboard.putBoolean("DiagnosticsBR", false);
			SmartDashboard.putBoolean(getName() + "Diagnostics", false);
			lastKnownState = DiagnosticsState.FAIL;
		}
		rightRearMotor.set(ControlMode.PercentOutput, 0.0);
		
		SmartDashboard.putBoolean("DiagnosticsFL", true);
		if(leftFrontMotor.getOutputCurrent() <= RobotMap.MINUMUM_MOTOR_CURR) {
			SmartDashboard.putBoolean("DiagnosticsFL", false);
			SmartDashboard.putBoolean(getName() + "Diagnostics", false);
			lastKnownState = DiagnosticsState.FAIL;
		}
		leftFrontMotor.set(ControlMode.PercentOutput, 0.0);
		
		SmartDashboard.putBoolean("DiagnosticsBL", true);
		if(leftRearMotor.getOutputCurrent() <= RobotMap.MINUMUM_MOTOR_CURR) {
			SmartDashboard.putBoolean("DiagnosticsBL", false);
			SmartDashboard.putBoolean(getName() + "Diagnostics", false);
			lastKnownState = DiagnosticsState.FAIL;
		}
		leftRearMotor.set(ControlMode.PercentOutput, 0.0);
	}
	
	@Override
	public void setDiagnosticsFlag(boolean state) {
		runDiagnostics = state;
	}
	
	@Override
	public boolean getDiagnosticsFlag() {
		return runDiagnostics;
	}
	
	@Override
	public void periodic() {
		if(telemetryState.getSelected() == SubsystemTelemetryState.ON) {
//			SmartDashboard.putNumber("ReadMotorCurrent", 
//					rightRearMotor.getOutputCurrent());
//			
			SmartDashboard.putNumber( "RightNativeUnits", 
					getRightNativeUnits());
			SmartDashboard.putNumber( "LeftNativeUnits", 
					getLeftNativeUnits());
			SmartDashboard.putNumber( "RightEncoderUnits", 
					getRightEncoderUnits());
			SmartDashboard.putNumber( "LeftEncoderUnits", 
					getLeftEncoderUnits());	
			
		}
		
	}

}

