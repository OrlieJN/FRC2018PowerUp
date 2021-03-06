package org.usfirst.frc.team4183.robot.subsystems.AutonomousSubsystem;

import java.util.List;

import org.usfirst.frc.team4183.robot.Robot;
import org.usfirst.frc.team4183.robot.RobotMap;
import org.usfirst.frc.team4183.robot.subsystems.BitBucketsSubsystem;
import org.usfirst.frc.team4183.robot.subsystems.AutonomousSubsystem.PathPlans.PathPlanChoice;
import org.usfirst.frc.team4183.utils.Positions;
import org.usfirst.frc.team4183.utils.Positions.GenericPositions;

import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.command.Command;
import edu.wpi.first.wpilibj.command.Subsystem;
import edu.wpi.first.wpilibj.smartdashboard.SendableChooser;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import jaci.pathfinder.Trajectory;
import jaci.pathfinder.followers.EncoderFollower;

/**
 *
 */
public class AutonomousSubsystem extends BitBucketsSubsystem 
{
	
	//these are used for the pathFollower
	private EncoderFollower pathTracker;
	private Trajectory lastSetTrajectory;
	
	public enum AutoChoices
	{
		PLAY_GAME,
		MOVE_TURN_TEST,
		DRIVE_PROFILE_TEST
	}
	private static SendableChooser<AutoChoices> autoChooser;
	
	public AutonomousSubsystem()
	{						
		
		
	}
	
	public void initTrajectoryFollower(Trajectory aTrajectory)
	{
		lastSetTrajectory = aTrajectory;
		pathTracker = new EncoderFollower(aTrajectory);
		pathTracker.configureEncoder((int)Robot.driveSubsystem.getLeftEncoderUnits(), RobotMap.DRIVE_MOTOR_NATIVE_TICKS_PER_REV/4, RobotMap.WHEEL_CIRCUMFERENCE_INCHES/.0254);
		pathTracker.configurePIDVA(1, 1, 1, 1, 1);
	}
	
	public void updatePathFollower()
	{
		pathTracker.calculate((int)Robot.driveSubsystem.getLeftEncoderUnits());
	}
	
	//checks to how much the path has been complete and sees if it is greater than or equal to the passed in parameter
	public boolean percentageComplete(double percentPathComplete)
	{
		return getPercentageComplete() >= percentPathComplete;
	}
	
	public double getPercentageComplete()
	{
		Trajectory.Segment currSegment = pathTracker.getSegment();
		
		int index = 0;
		for (int i = 0; i < lastSetTrajectory.segments.length; i++)
		{
			if (currSegment == lastSetTrajectory.segments[i])
			{
				index = i;
				break;
			}
		}
		return index/lastSetTrajectory.segments.length;
	}

	public void initialize()
	{		
		autoChooser = new SendableChooser<AutoChoices>();
		autoChooser.addDefault("PLAY GAME", AutoChoices.PLAY_GAME);
		autoChooser.addObject("MOVE TURN TEST", AutoChoices.MOVE_TURN_TEST);
		autoChooser.addObject("DRIVE PROFILE TEST", AutoChoices.DRIVE_PROFILE_TEST);
		SmartDashboard.putData( "Auto Choices", autoChooser);
		
		PathPlans.initialize();	
	}
	
	public AutoChoices getAutoChoice()
	{
		return autoChooser.getSelected();
	}
		
	public Positions.GenericPositions getSwitchPosition()
	{
		String gameData;
		gameData = DriverStation.getInstance().getGameSpecificMessage();
		if (gameData.length() > 0)
		{
			if (gameData.charAt(0) == 'L')
			{
				return GenericPositions.LEFT;
			}
			else
			{
				return GenericPositions.RIGHT;
			}
		}
		else
		{
			return GenericPositions.UNKNOWN;
		}
	}

	public Positions.GenericPositions getScalePosition()
	{
		String gameData;
		gameData = DriverStation.getInstance().getGameSpecificMessage();
		if (gameData.length() > 1)
		{
			if (gameData.charAt(1) == 'L')
			{
				return GenericPositions.LEFT;
			}
			else
			{
				return GenericPositions.RIGHT;
			}
		}
		else
		{
			return GenericPositions.UNKNOWN;
		}
	}
	
    public void initDefaultCommand() 
    {
        //NO, not here. setDefaultCommand(new Idle());
    	//Create a single instance only when auto starts
    }
    
    public void pressOuttakeButton()
    {
    	Robot.oi.sbtnOuttakeThroat.push();
    }
    
    public void releaseOuttakeButton()
    {
    	Robot.oi.sbtnOuttakeThroat.release();
    }

	@Override
	public void diagnosticsInit() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void diagnosticsExecute() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void diagnosticsCheck() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void setDiagnosticsFlag(boolean enable) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public boolean getDiagnosticsFlag() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void periodic() 
	{
		// TODO Insert processing to decide if re-planning is needed, and to raise the flag
		// Need to decide how to synchronize the plan updates (if needed) with the state processing.
		// it is possible that the re-plan is a state that is held until the replan flag is dropped.
		//
		// NOTE: If planning will take a long time, then it should be pushed to a separate thread
		// and monitored here, lowering the planning flag when the work is done. This keeps the
		// WPI scheduler running as expected (allowing the system to function within the parameters
		// established by the FMS, DriveStation, and the WPI architecture.
		
	}

	public void start() {
		
		// Don't think of this as a command but rather a state
		Idle initialCommand = new Idle();
		initialCommand.start();		
	}

	
}

