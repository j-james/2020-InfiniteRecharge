package com.spartronics4915.frc2020.commands;

import com.spartronics4915.frc2020.subsystems.PanelRotator;

import edu.wpi.first.wpilibj2.command.CommandBase;
import edu.wpi.first.wpilibj2.command.FunctionalCommand;

public class PanelRotatorCommands
{
    /**
     * Commands with simple logic statements should be implemented as a
     * FunctionalCommand. This saves the overhead of a full CommandBase, but still
     * allows us to deal with isFinished.
     */
    public class Raise extends FunctionalCommand
    {
        public Raise(PanelRotator PanelRotator)
        {
            super(() -> {}, PanelRotator::raise, (Boolean b) -> PanelRotator.stop(),
                PanelRotator::getBeamSensorUp, PanelRotator);
        }
    }

    public class Lower extends FunctionalCommand
    {
        public Lower(PanelRotator PanelRotator)
        {
            super(() -> {}, PanelRotator::lower, (Boolean b) -> PanelRotator.stop(),
                PanelRotator::getBeamSensorDown, PanelRotator);
        }
    }

    // TODO: This might be better as a FunctionalCommand
    public class SpinToColor extends CommandBase
    {
        private final PanelRotator mPanelRotator;
        private String mTargetColor;

        // You should only use one subsystem per command. If multiple are needed, use a
        // CommandGroup.
        public SpinToColor(PanelRotator PanelRotator)
        {
            mPanelRotator = PanelRotator;
            addRequirements(mPanelRotator);
        }

        // Called when the command is initially scheduled.
        @Override
        public void initialize()
        {
            mTargetColor = mPanelRotator.getTargetColor();
        }

        // Called every time the scheduler runs while the command is scheduled.
        @Override
        public void execute()
        {
            mPanelRotator.spin();
        }

        // Returns true when the command should end.
        @Override
        public boolean isFinished()
        {
            // Note that this is a comparison of Strings.
            // Conversions from native ColorSensorV3 values to one of four values is done
            // in PanelRotator
            if (mPanelRotator.getActualColor() == mTargetColor)
                return true;
            else
                return false;
        }

        // Called once the command ends or is interrupted.
        @Override
        public void end(boolean interrupted)
        {
            mPanelRotator.stop();
        }
    }

    /**
     * Commands that are "complex", or have > simple logic within them,
     * should be put here.
     *
     * An example of this is the SpinRotationsCommand.
     */
    public class SpinRotation extends CommandBase
    {
        private final PanelRotator mPanelRotator;

        private int eighths;
        private String lastColor;
        private String currentColor;

        // You should only use one subsystem per command. If multiple are needed, use a
        // CommandGroup.
        public SpinRotation(PanelRotator PanelRotator)
        {
            mPanelRotator = PanelRotator;
            addRequirements(mPanelRotator);
        }

        // Called when the command is initially scheduled.
        @Override
        public void initialize()
        {
            eighths = 0;
            currentColor = mPanelRotator.getActualColor();
            lastColor = currentColor;
        }

        // Called every time the scheduler runs while the command is scheduled.
        @Override
        public void execute()
        {
            mPanelRotator.spin();
        }

        // Returns true when the command should end.
        @Override
        public boolean isFinished()
        {
            currentColor = mPanelRotator.getActualColor();
            if (currentColor != lastColor)
                eighths++;
            lastColor = currentColor;

            if (eighths == 8) // TODO: double check for off-by-one errors
                return true;
            else
                return false;
        }

        // Called once the command ends or is interrupted.
        @Override
        public void end(boolean interrupted)
        {
            mPanelRotator.stop();
        }
    }
}
