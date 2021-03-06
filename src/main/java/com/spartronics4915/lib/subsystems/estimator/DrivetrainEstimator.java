package com.spartronics4915.lib.subsystems.estimator;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.function.BiConsumer;

import com.spartronics4915.lib.math.twodim.geometry.Pose2d;
import com.spartronics4915.lib.math.twodim.geometry.Rotation2d;
import com.spartronics4915.lib.util.VecBuilder;
import edu.wpi.first.wpilibj.Timer;
import edu.wpi.first.wpilibj.estimator.ExtendedKalmanFilter;
import edu.wpi.first.wpilibj.kinematics.DifferentialDriveWheelSpeeds;
import edu.wpi.first.wpilibj.estimator.KalmanFilterLatencyCompensator;
import edu.wpi.first.wpilibj.math.Discretization;
import edu.wpi.first.wpilibj.math.StateSpaceUtil;
import edu.wpi.first.wpiutil.math.MatBuilder;
import edu.wpi.first.wpiutil.math.Matrix;
import edu.wpi.first.wpiutil.math.MatrixUtils;
import edu.wpi.first.wpiutil.math.Nat;
import edu.wpi.first.wpiutil.math.numbers.N1;
import edu.wpi.first.wpiutil.math.numbers.N3;
import edu.wpi.first.wpiutil.math.numbers.N5;
import edu.wpi.first.wpiutil.math.numbers.N6;

/**
 * This class wraps an Extended Kalman Filter to fuse latency-compensated vision
 * measurements with differential drive encoder measurements. It will correct
 * for noisy vision measurements and encoder drift. It is intended to be an easy
 * drop-in for
 * {@link edu.wpi.first.wpilibj.kinematics.DifferentialDriveOdometry}; in fact,
 * if you never call {@link DrivetrainEstimator#addVisionMeasurement}
 * and only call {@link DrivetrainEstimator#update} then this will
 * behave exactly the same as DifferentialDriveOdometry.
 *
 * <p>{@link DrivetrainEstimator#update} should be called every robot
 * loop (if your robot loops are faster than the default then you should change
 * the {@link DrivetrainEstimator#DrivetrainEstimator(Rotation2d, Pose2d,
 * Matrix, Matrix, Matrix, double) nominal delta time}.)
 * {@link DrivetrainEstimator#addVisionMeasurement} can be called as
 * infrequently as you want; if you never call it then this class will behave
 * exactly like regular encoder odometry.
 *
 * <p>Our state-space system is:
 *
 * <p>x = [[x, y, theta, dist_l, dist_r]]^T in the field coordinate system (dist_* are wheel
 * distances.)
 *
 * <p>u = [[vx, vy, omega]]^T (robot-relative velocities) -- NB: using velocities make things
 * considerably easier, because it means that teams don't have to worry about getting an accurate
 * model. Basically, we suspect that it's easier for teams to get good encoder data than it is for
 * them to perform system identification well enough to get a good model.
 *
 * <p>y = [[x, y, theta]]^T from vision, or y = [[x_s, y_s, theta_s, dist_l, dist_r, theta_g]] from
 * encoders, VSLAM, and gyro (subscript s indicates VSLAM as the source)
 */
public class DrivetrainEstimator {
    private final ExtendedKalmanFilter<N5, N3, N6> m_observer;
    private final BiConsumer<Matrix<N3, N1>, Matrix<N3, N1>> m_visionCorrect;
    private final KalmanFilterLatencyCompensator<N5, N3, N6> m_latencyCompensator;

    private final double m_nominalDt; // Seconds
    private double m_prevTimeSeconds = -1.0;

    private Rotation2d m_gyroOffset;
    private Rotation2d m_previousAngle;

    /**
     * Constructs a DrivetrainEstimator.
     *
     * @param gyroAngle                The current gyro angle.
     * @param initialPoseMeters        The starting pose estimate.
     * @param stateStdDevs             Standard deviations of model states. Increase these numbers to
     *                                 trust your wheel and gyro velocities less.
     * @param localMeasurementStdDevs  Standard deviations of the encoder and gyro measurements.
     *                                 Increase these numbers to trust encoder distances and gyro
     *                                 angle less.
     * @param visionMeasurementStdDevs Standard deviations of the encoder measurements. Increase
     *                                 these numbers to trust vision less.
     */
    public DrivetrainEstimator(
            Rotation2d gyroAngle, Pose2d initialPoseMeters,
            Matrix<N5, N1> stateStdDevs,
            Matrix<N6, N1> localMeasurementStdDevs, Matrix<N3, N1> visionMeasurementStdDevs
    ) {
        this(gyroAngle, initialPoseMeters,
                stateStdDevs, localMeasurementStdDevs, visionMeasurementStdDevs, 0.02);
    }

    /**
     * Constructs a DrivetrainEstimator.
     *
     * @param gyroAngle                The current gyro angle.
     * @param initialPoseMeters        The starting pose estimate.
     * @param stateStdDevs             Standard deviations of model states. Increase these numbers to
     *                                 trust your wheel and gyro velocities less.
     * @param localMeasurementStdDevs  Standard deviations of the encoder and gyro measurements.
     *                                 Increase these numbers to trust encoder distances and gyro
     *                                 angle less.
     * @param visionMeasurementStdDevs Standard deviations of the encoder measurements. Increase
     *                                 these numbers to trust vision less.
     * @param nominalDtSeconds         The time in seconds between each robot loop.
     */
    @SuppressWarnings("ParameterName")
    public DrivetrainEstimator(
            Rotation2d gyroAngle, Pose2d initialPoseMeters,
            Matrix<N5, N1> stateStdDevs,
            Matrix<N6, N1> localMeasurementStdDevs, Matrix<N3, N1> visionMeasurementStdDevs,
            double nominalDtSeconds
    ) {
        m_nominalDt = nominalDtSeconds;

        m_observer = new ExtendedKalmanFilter<>(
                Nat.N5(), Nat.N3(), Nat.N6(),
                this::f,
                (x, u) -> VecBuilder.fill(
                        x.get(0, 0), x.get(1, 0), x.get(2, 0),
                        x.get(3, 0), x.get(4, 0),
                        x.get(2, 0)
                ),
                stateStdDevs, localMeasurementStdDevs,
                m_nominalDt
        );
        m_latencyCompensator = new KalmanFilterLatencyCompensator<>();

        var visionContR = StateSpaceUtil.makeCovarianceMatrix(Nat.N3(), visionMeasurementStdDevs);
        var visionDiscR = Discretization.discretizeR(visionContR, m_nominalDt);
        m_visionCorrect = (u, y) -> m_observer.correct(
                Nat.N3(), u, y,
                (x, u_) -> new Matrix<N3, N1>(x.getStorage().extractMatrix(0, 3, 0, 1)),
                visionDiscR
        );

        m_gyroOffset = initialPoseMeters.getRotation().rotateBy(gyroAngle.inverse());
        m_previousAngle = initialPoseMeters.getRotation();
        m_observer.setXhat(fillStateVector(initialPoseMeters, 0.0, 0.0));
    }

    @SuppressWarnings({"ParameterName", "MethodName"})
    private Matrix<N5, N1> f(Matrix<N5, N1> x, Matrix<N3, N1> u) {
        // Apply a rotation matrix. Note that we do *not* add x--Runge-Kutta does that for us.
        var theta = x.get(2, 0);
        var toFieldRotation = new MatBuilder<>(Nat.N5(), Nat.N5()).fill(
                Math.cos(theta), -Math.sin(theta), 0, 0, 0,
                Math.sin(theta), Math.cos(theta), 0, 0, 0,
                0, 0, 1, 0, 0,
                0, 0, 0, 1, 0,
                0, 0, 0, 0, 1
        );
        return toFieldRotation.times(VecBuilder.fill(
                u.get(0, 0), u.get(1, 0), u.get(2, 0), u.get(0, 0), u.get(1, 0)
        ));
    }

    /**
     * Resets the robot's position on the field.
     *
     * <p>You NEED to reset your encoders (to zero) when calling this method.
     *
     * <p>The gyroscope angle does not need to be reset here on the user's robot code.
     * The library automatically takes care of offsetting the gyro angle.
     *
     * @param poseMeters The position on the field that your robot is at.
     * @param gyroAngle  The angle reported by the gyroscope.
     */
    public void resetPosition(Pose2d poseMeters, Rotation2d gyroAngle) {
        m_previousAngle = poseMeters.getRotation();
        m_gyroOffset = getEstimatedPosition().getRotation().rotateBy(gyroAngle.inverse());
        m_observer.setXhat(fillStateVector(poseMeters, 0.0, 0.0));
    }

    /**
     * Gets the pose of the robot at the current time as estimated by the Extended Kalman Filter.
     *
     * @return The estimated robot pose in meters.
     */
    public Pose2d getEstimatedPosition() {
        return new Pose2d(
                m_observer.getXhat(0),
                m_observer.getXhat(1),
                Rotation2d.fromRadians(m_observer.getXhat(2))
        );
    }

    /**
     * Add a vision measurement to the Extended Kalman Filter. This will correct the
     * odometry pose estimate while still accounting for measurement noise.
     *
     * <p>This method can be called as infrequently as you want, as long as you are
     * calling {@link DrivetrainEstimator#update} every loop.
     *
     * @param visionRobotPoseMeters The pose of the robot as measured by the vision
     *                              camera.
     * @param timestampSeconds      The timestamp of the vision measurement in seconds. Note that if
     *                              you don't use your own time source by calling
     *                              {@link DrivetrainEstimator#updateWithTime} then you
     *                              must use a timestamp with an epoch since FPGA startup
     *                              (i.e. the epoch of this timestamp is the same epoch as
     *                              {@link edu.wpi.first.wpilibj.Timer#getFPGATimestamp
     *                              Timer.getFPGATimestamp}.) This means that you should
     *                              use Timer.getFPGATimestamp as your time source in
     *                              this case.
     */
    public void addVisionMeasurement(Pose2d visionRobotPoseMeters, double timestampSeconds) {
        var oldXHat = m_observer.getXhat();
        var oldP = m_observer.getP();
        var oldSnapshots = new ArrayList<>(m_latencyCompensator.m_pastObserverSnapshots);

	// You're probably looking at this thinking "WTF is in that catch block"
	// Well... There was a bug that I couldn't track down so I used all this code to dump the EKF state
	// Hopefully I will have found the bug and fixed it by the time you're reading this, and then you can remove the stuff in the catch block
        try {
            m_latencyCompensator.applyPastGlobalMeasurement(
                    Nat.N3(),
                    m_observer, m_nominalDt,
                    VecBuilder.fill(
                            visionRobotPoseMeters.getTranslation().getX(),
                            visionRobotPoseMeters.getTranslation().getY(),
                            visionRobotPoseMeters.getRotation().getRadians()
                    ),
                    m_visionCorrect,
                    timestampSeconds
            );
        } catch (Exception e) {
            try (PrintWriter writer = new PrintWriter(new File("/home/lvuser/out.txt"))) {
                writer.println("y:");
                writer.println(VecBuilder.fill(
                        visionRobotPoseMeters.getTranslation().getX(),
                        visionRobotPoseMeters.getTranslation().getY(),
                        visionRobotPoseMeters.getRotation().getRadians()
                ).toString());

                writer.println("Timestamp:");
                writer.println(timestampSeconds);

                writer.println("xHat old:");
                writer.println(oldXHat);

                writer.println("P old:");
                writer.println(oldP);

                writer.println("xHat:");
                writer.println(m_observer.getXhat());

                writer.println("P:");
                writer.println(m_observer.getP());

                writer.println("Snapshots old:");
                oldSnapshots.forEach((it) -> {
                    var snap = it.getValue();
                    writer.println(snap.xHat);
                    writer.println(snap.errorCovariances);
                    writer.println(snap.inputs);
                    writer.println(snap.localMeasurements);
                    writer.println("=======");
                });

                writer.println("Snapshots:");
                writer.println(m_latencyCompensator.m_pastObserverSnapshots.size());
                m_latencyCompensator.m_pastObserverSnapshots.forEach((it) -> {
                    var snap = it.getValue();
                    writer.println(snap.xHat);
                    writer.println(snap.errorCovariances);
                    writer.println(snap.inputs);
                    writer.println(snap.localMeasurements);
                    writer.println("=======");
                });

                writer.println("done");
                writer.flush();
            } catch (FileNotFoundException ex) {
                ex.printStackTrace();
            }
        }
    }

    /**
     * Updates the the Extended Kalman Filter using only wheel encoder information.
     * Note that this should be called every loop.
     *
     * @param gyroAngle                      The current gyro angle.
     * @param wheelVelocitiesMetersPerSecond The velocities of the wheels in meters per second.
     * @param distanceLeftMeters             The total distance travelled by the left wheel in meters
     *                                       since the last time you called
     *                                       {@link DrivetrainEstimator#resetPosition}.
     * @param distanceRightMeters            The total distance travelled by the right wheel in meters
     *                                       since the last time you called
     *                                       {@link DrivetrainEstimator#resetPosition}.
     * @return The estimated pose of the robot in meters.
     */
    public Pose2d update(
            Rotation2d gyroAngle,
            DifferentialDriveWheelSpeeds wheelVelocitiesMetersPerSecond,
            Pose2d slamraPose,
            double distanceLeftMeters, double distanceRightMeters
    ) {
        return updateWithTime(
                Timer.getFPGATimestamp(), gyroAngle, wheelVelocitiesMetersPerSecond, slamraPose,
                distanceLeftMeters, distanceRightMeters
        );
    }

    /**
     * Updates the the Extended Kalman Filter using only wheel encoder information.
     * Note that this should be called every loop.
     *
     * @param currentTimeSeconds             Time at which this method was called, in seconds.
     * @param gyroAngle                      The current gyro angle.
     * @param wheelVelocitiesMetersPerSecond The velocities of the wheels in meters per second.
     * @param distanceLeftMeters             The total distance travelled by the left wheel in meters
     *                                       since the last time you called
     *                                       {@link DrivetrainEstimator#resetPosition}.
     * @param distanceRightMeters            The total distance travelled by the right wheel in meters
     *                                       since the last time you called
     *                                       {@link DrivetrainEstimator#resetPosition}.
     * @return The estimated pose of the robot in meters.
     */
    @SuppressWarnings({"LocalVariableName", "ParameterName"})
    public Pose2d updateWithTime(
            double currentTimeSeconds, Rotation2d gyroAngle,
            DifferentialDriveWheelSpeeds wheelVelocitiesMetersPerSecond,
            Pose2d slamraPose,
            double distanceLeftMeters, double distanceRightMeters
    ) {
        double dt = m_prevTimeSeconds >= 0 ? currentTimeSeconds - m_prevTimeSeconds : m_nominalDt;
        m_prevTimeSeconds = currentTimeSeconds;

        var angle = gyroAngle.rotateBy(m_gyroOffset);
        // Diff drive forward kinematics:
        // v_c = (v_l + v_r) / 2
        var wheelVels = wheelVelocitiesMetersPerSecond;
        var u = VecBuilder.fill(
                (wheelVels.leftMetersPerSecond + wheelVels.rightMetersPerSecond) / 2, 0,
                angle.rotateBy(m_previousAngle.inverse()).getRadians() / dt
        );
        m_previousAngle = angle;

        var localY = VecBuilder.fill(
                slamraPose.getTranslation().getX(), slamraPose.getTranslation().getY(), slamraPose.getRotation().getRadians(),
                distanceLeftMeters, distanceRightMeters, angle.getRadians()
        );
        m_latencyCompensator.addObserverState(m_observer, u, localY, currentTimeSeconds);
        m_observer.predict(u, dt);
        m_observer.correct(u, localY);

        return getEstimatedPosition();
    }

    private static Matrix<N5, N1> fillStateVector(Pose2d pose, double leftDist, double rightDist) {
        return VecBuilder.fill(
                pose.getTranslation().getX(),
                pose.getTranslation().getY(),
                pose.getRotation().getRadians(),
                leftDist,
                rightDist
        );
    }
}
