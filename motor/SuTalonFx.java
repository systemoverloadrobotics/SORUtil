package frc.sorutil.motor;

import java.util.logging.Logger;

import com.ctre.phoenix.motorcontrol.FeedbackDevice;
import com.ctre.phoenix.motorcontrol.IFollower;
import com.ctre.phoenix.motorcontrol.IMotorController;
import com.ctre.phoenix.motorcontrol.NeutralMode;
import com.ctre.phoenix.motorcontrol.StatorCurrentLimitConfiguration;
import com.ctre.phoenix.motorcontrol.can.WPI_TalonFX;

import edu.wpi.first.wpilibj.motorcontrol.MotorController;
import frc.sorutil.Errors;
import frc.sorutil.motor.SensorConfiguration.ConnectedSensorSource;
import frc.sorutil.motor.SensorConfiguration.ExternalSensorSource;
import frc.sorutil.motor.SensorConfiguration.IntegratedSensorSource;

public class SuTalonFx extends SuController {
  private static final double DEFAULT_CURRENT_LIMIT = 80;
  private static final double DEFAULT_NEUTRAL_DEADBAND = 0.04;
  private static final double COUNTS_PER_REVOLUTION_INTEGRATED = 2048;

  private final WPI_TalonFX talon;
  private MotorConfiguration config;

  private boolean voltageControlOverrideSet = false;
  private Double lastVoltageCompensation = null;

  private SuController.ControlMode lastMode;
  private double lastSetpoint;

  private SensorConfiguration sensorConfig;

  public SuTalonFx(WPI_TalonFX talon, String name, MotorConfiguration motorConfig, SensorConfiguration sensorConfig) {
    super(talon, motorConfig, sensorConfig,
        Logger.getLogger(String.format("TalonFX(%d: %s)", talon.getDeviceID(), name)));

    this.talon = talon;
  }

  @Override
  public void configure(MotorConfiguration config, SensorConfiguration sensorConfig) {
    Errors.handleCtre(talon.clearMotionProfileHasUnderrun(), logger, "clearing motion profile");
    Errors.handleCtre(talon.clearMotionProfileTrajectories(), logger, "clearing motion profile trajectories");

    Errors.handleCtre(talon.clearStickyFaults(), logger, "clearing sticky faults");

    Errors.handleCtre(talon.configFactoryDefault(), logger, "resetting motor config");

    talon.setInverted(config.inverted());

    Errors.handleCtre(talon.config_kP(0, config.pidProfile().p()), logger, "setting P constant");
    Errors.handleCtre(talon.config_kI(0, config.pidProfile().i()), logger, "setting I constant");
    Errors.handleCtre(talon.config_kD(0, config.pidProfile().d()), logger, "setting D constant");
    Errors.handleCtre(talon.config_kF(0, config.pidProfile().f()), logger, "setting F constant");

    double limit = DEFAULT_CURRENT_LIMIT;
    if (config.currentLimit() != null) {
      limit = config.currentLimit();
    }

    StatorCurrentLimitConfiguration limitConfig = new StatorCurrentLimitConfiguration();
    limitConfig.currentLimit = limit;
    limitConfig.triggerThresholdCurrent = limit;

    Errors.handleCtre(talon.configStatorCurrentLimit(limitConfig), logger, "setting current limit");
    restoreDefaultVoltageCompensation();

    NeutralMode desiredMode = NeutralMode.Coast;
    if (config.idleMode() == IdleMode.BRAKE) {
      desiredMode = NeutralMode.Brake;
    }
    talon.setNeutralMode(desiredMode);

    double neutralDeadband = DEFAULT_NEUTRAL_DEADBAND;
    if (config.neutralDeadband() != null) {
      neutralDeadband = config.neutralDeadband();
    }
    Errors.handleCtre(talon.configNeutralDeadband(neutralDeadband), logger, "setting neutral deadband");

    Errors.handleCtre(talon.configPeakOutputForward(config.maxOutput()), logger, "configuring max output");
    Errors.handleCtre(talon.configPeakOutputReverse(-config.maxOutput()), logger, "configuring max output");

    if (sensorConfig != null) {
      if (sensorConfig.source() instanceof ConnectedSensorSource) {
        throw new MotorConfigurationError(
            "Talon FX does not supported directly connected sensors, but was configured to use one.");
      }

      if (sensorConfig.source() instanceof IntegratedSensorSource) {
        Errors.handleCtre(talon.configSelectedFeedbackSensor(FeedbackDevice.IntegratedSensor), logger,
            "configuring sensor to integrated feedback sensor");
      }

      if (sensorConfig.source() instanceof ExternalSensorSource) {
        configureSoftPid();
      }
    }
  }

  @Override
  public MotorController rawController() {
    return talon;
  }

  @Override
  public void tick() {
    Errors.handleCtre(talon.getLastError(), logger, "in motor loop, likely from setting a motor update");

    if (talon.hasResetOccurred()) {

    }

    if (softPidControllerEnabled) {
      double current = 0;
      if (softPidControllerMode) {
        // velocity mode
        current = ((ExternalSensorSource) sensorConfig.source()).sensor.velocity();
      } else {
        // position mode
        current = ((ExternalSensorSource) sensorConfig.source()).sensor.position();
      }
      double output = softPidController.calculate(current);
      talon.set(com.ctre.phoenix.motorcontrol.ControlMode.PercentOutput, output);
    }
  }

  private void restoreDefaultVoltageCompensation() {
    Errors.handleCtre(talon.configVoltageCompSaturation(SuController.DEFAULT_VOLTAGE_COMPENSTAION),
        logger, "configuring voltage compenstation");
    talon.enableVoltageCompensation(config.voltageCompenstationEnabled());
  }

  @Override
  public void set(SuController.ControlMode mode, double setpoint) {
    if (voltageControlOverrideSet && mode != SuController.ControlMode.VOLTAGE) {
      restoreDefaultVoltageCompensation();
      voltageControlOverrideSet = false;
      lastVoltageCompensation = null;
    }

    // Skip updating the motor if the setpoint is the same, this reduces
    // unneccessary CAN messages.
    if (setpoint == lastSetpoint && mode == lastMode) {
      return;
    }
    lastSetpoint = setpoint;
    lastMode = mode;
    softPidControllerEnabled = false;

    switch (mode) {
      case PERCENT_OUTPUT:
        talon.set(com.ctre.phoenix.motorcontrol.ControlMode.PercentOutput, setpoint);
        break;
      case POSITION:
        setPosition(setpoint);
        break;
      case VELOCITY:
        setVelocity(setpoint);
        break;
      case VOLTAGE:
        boolean negative = setpoint < 0;
        double abs = Math.abs(setpoint);
        if (lastVoltageCompensation != null && setpoint != lastVoltageCompensation) {
          Errors.handleCtre(talon.configVoltageCompSaturation(abs), logger,
              "configuring voltage compenstation for voltage control");
          lastVoltageCompensation = setpoint;
        }
        if (!voltageControlOverrideSet) {
          talon.enableVoltageCompensation(true);
          voltageControlOverrideSet = true;
        }
        talon.set(com.ctre.phoenix.motorcontrol.ControlMode.PercentOutput, negative ? -1 : 1);
        break;
    }
  }

  private void setPosition(double setpoint) {
    // Using the integrated Falcon source
    if (sensorConfig.source() instanceof SensorConfiguration.IntegratedSensorSource) {
      var integrated = (SensorConfiguration.IntegratedSensorSource) sensorConfig.source();
      double motorDegrees = integrated.outputOffset * setpoint;
      double countsToDegrees = COUNTS_PER_REVOLUTION_INTEGRATED / 360.0;
      double output = motorDegrees * countsToDegrees;

      talon.set(com.ctre.phoenix.motorcontrol.ControlMode.Position, output);
      return;
    }
    // Using sensor external to the Falcon.
    if (sensorConfig.source() instanceof SensorConfiguration.ExternalSensorSource) {
      softPidControllerEnabled = true;
      softPidControllerMode = false;

      double current = ((ExternalSensorSource) sensorConfig.source()).sensor.position();
      double output = softPidController.calculate(current, setpoint);
      talon.set(com.ctre.phoenix.motorcontrol.ControlMode.PercentOutput, output);
      return;
    }
    throw new MotorConfigurationError(
        "unkonwn type of sensor configuration: " + sensorConfig.source().getClass().getName());
  }

  private void setVelocity(double setpoint) {
    // Using the integrated Falcon source
    if (sensorConfig.source() instanceof SensorConfiguration.IntegratedSensorSource) {
      var integrated = (SensorConfiguration.IntegratedSensorSource) sensorConfig.source();
      double motorRpm = integrated.outputOffset * setpoint;
      double motorRps = motorRpm / 60.0;
      double output = (COUNTS_PER_REVOLUTION_INTEGRATED * motorRps) / 10.0;

      talon.set(com.ctre.phoenix.motorcontrol.ControlMode.Velocity, output);
      return;
    }
    // Using sensor external to the Falcon.
    if (sensorConfig.source() instanceof SensorConfiguration.ExternalSensorSource) {
      softPidControllerEnabled = true;
      softPidControllerMode = true;

      double current = ((ExternalSensorSource) sensorConfig.source()).sensor.velocity();
      double output = softPidController.calculate(current, setpoint);
      talon.set(com.ctre.phoenix.motorcontrol.ControlMode.PercentOutput, output);
      return;
    }
    throw new MotorConfigurationError(
        "unkonwn type of sensor configuration: " + sensorConfig.source().getClass().getName());
  }

  @Override
  public void stop() {
    talon.stopMotor();
  }

  @Override
  public void follow(SuController other) {
    if (!(other.rawController() instanceof IFollower)) {
      throw new MotorConfigurationError(
          "CTRE motor controllers can only follow other motor controllers from CTRE");
    }

    talon.follow((IMotorController) other.rawController());
  }

  @Override
  public double outputPosition() {
    if (sensorConfig.source() instanceof IntegratedSensorSource) {
      return talon.getSelectedSensorPosition() / (COUNTS_PER_REVOLUTION_INTEGRATED * 360.0);
    }
    if (sensorConfig.source() instanceof ExternalSensorSource) {
      return ((ExternalSensorSource) sensorConfig.source()).sensor.position();
    }
    return 0;
  }

  @Override
  public double outputVelocity() {
    if (sensorConfig.source() instanceof IntegratedSensorSource) {
      return talon.getSelectedSensorVelocity() * 10.0 * 60.0 / COUNTS_PER_REVOLUTION_INTEGRATED;
    }
    if (sensorConfig.source() instanceof ExternalSensorSource) {
      return ((ExternalSensorSource) sensorConfig.source()).sensor.velocity();
    }
    return 0;
  }

  @Override
  public void setSensorPosition(double position) {
    if (sensorConfig.source() instanceof IntegratedSensorSource) {
      // TODO: this isn't quite right. It should be actually modifying this better.
      talon.configIntegratedSensorOffset(position % 360);
    }
    if (sensorConfig.source() instanceof ExternalSensorSource) {
      ((ExternalSensorSource) sensorConfig.source()).sensor.setPosition(position);
    }
  }
}
