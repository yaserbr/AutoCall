import { useEffect, useRef, useState } from "react";
import {
  PermissionsAndroid,
  Platform,
  Pressable,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from "react-native";
import {
  type AutoAnswerStatus,
  disableAutoAnswer,
  enableAutoAnswer,
  endCurrentCall,
  getStatus,
  startSimpleCall,
} from "../../src/native/autoCallNative";

const SERVER = "https://serverautocall-production.up.railway.app";
const DEVICE_UID = "device_123";
const POLL_INTERVAL_MS = 10000;
const DEFAULT_PHONE = "05";
const DEFAULT_HANGUP_SECONDS = 20;
const DEFAULT_SIMPLE_CALL_DURATION_SECONDS = 20;
const MAX_SIMPLE_CALL_DURATION_SECONDS = 3600;
const LOG_PREFIX = "[AutoCall/UI]";

type ServerCallCommand = {
  id: string;
  deviceUid: string;
  action?: "call" | "end";
  type: "CALL" | "END";
  phoneNumber?: string | null;
  durationSeconds?: number | null;
  status: string;
  scheduledAt?: string;
};

type UiState = {
  autoAnswerEnabled: boolean;
  autoHangupSeconds: number;
  hangupScheduled: boolean;
  lastEvent: string;
  lastEventAt: number;
};

const emptyState: UiState = {
  autoAnswerEnabled: false,
  autoHangupSeconds: DEFAULT_HANGUP_SECONDS,
  hangupScheduled: false,
  lastEvent: "Idle",
  lastEventAt: 0,
};

const logEvent = (event: string, payload?: unknown) => {
  if (payload !== undefined) {
    console.log(`${LOG_PREFIX} ${event}`, payload);
    return;
  }
  console.log(`${LOG_PREFIX} ${event}`);
};

const normalizePhoneNumber = (input: string): string | null => {
  const trimmed = input.trim();
  if (!trimmed) return null;

  const normalized = trimmed.replace(/[^\d+]/g, "");
  const plusCount = (normalized.match(/\+/g) ?? []).length;
  if (plusCount > 1 || (plusCount === 1 && !normalized.startsWith("+"))) {
    return null;
  }

  const digits = normalized.replace(/\+/g, "");
  if (!digits) return null;

  return normalized;
};

const formatTime = (timestamp: number): string => {
  if (!timestamp) return "--";
  return new Date(timestamp).toLocaleString();
};

export default function AutoCallScreen() {
  const [uiState, setUiState] = useState<UiState>(emptyState);
  const [statusMessage, setStatusMessage] = useState("Ready");
  const [phoneNumber, setPhoneNumber] = useState(DEFAULT_PHONE);
  const [autoHangupSecondsText, setAutoHangupSecondsText] = useState(String(DEFAULT_HANGUP_SECONDS));
  const [simpleCallDurationSecondsText, setSimpleCallDurationSecondsText] = useState(
    String(DEFAULT_SIMPLE_CALL_DURATION_SECONDS)
  );
  const [nextServerCommand, setNextServerCommand] = useState<ServerCallCommand | null>(null);
  const inFlightCommandIds = useRef<Set<string>>(new Set());

  const updateUiFromStatus = (status: AutoAnswerStatus) => {
    setUiState({
      autoAnswerEnabled: status.enabled,
      autoHangupSeconds: status.autoHangupSeconds,
      hangupScheduled: status.hangupScheduled,
      lastEvent: status.lastEvent,
      lastEventAt: status.lastEventAt,
    });
    setAutoHangupSecondsText(String(status.autoHangupSeconds));
  };

  const refreshStatus = async () => {
    try {
      const status = await getStatus();
      updateUiFromStatus(status);
      logEvent("status_refresh", status);
    } catch (error) {
      logEvent("status_refresh_failed", { error });
      setStatusMessage("Failed to load auto-answer status");
    }
  };

  const requestAndroidPermissions = async (): Promise<boolean> => {
    if (Platform.OS !== "android") return true;

    const permissions = [
      PermissionsAndroid.PERMISSIONS.CALL_PHONE,
      PermissionsAndroid.PERMISSIONS.ANSWER_PHONE_CALLS,
      PermissionsAndroid.PERMISSIONS.READ_PHONE_STATE,
    ];

    for (const permission of permissions) {
      const granted = await PermissionsAndroid.request(permission);
      if (granted !== PermissionsAndroid.RESULTS.GRANTED) {
        setStatusMessage(`Permission denied: ${permission}`);
        return false;
      }
    }

    if (Platform.Version >= 33) {
      await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
    }

    return true;
  };

  const registerDevice = async () => {
    try {
      await fetch(`${SERVER}/devices/register`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ deviceUid: DEVICE_UID }),
      });
    } catch (error) {
      logEvent("register_device_failed", { error });
    }
  };

  const sendHeartbeat = async () => {
    try {
      await fetch(`${SERVER}/devices/heartbeat`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ deviceUid: DEVICE_UID }),
      });
    } catch (error) {
      logEvent("heartbeat_failed", { error });
    }
  };

  const updateCommandStatus = async (id: string, status: string) => {
    try {
      await fetch(`${SERVER}/commands/${id}/status`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ status }),
      });
    } catch (error) {
      logEvent("update_command_status_failed", { id, status, error });
    }
  };

  const parseSimpleCallDurationMs = (): number | null => {
    const trimmed = simpleCallDurationSecondsText.trim();
    if (!trimmed) {
      return null;
    }

    const parsedSeconds = Number.parseInt(trimmed, 10);
    if (Number.isNaN(parsedSeconds) || parsedSeconds <= 0) {
      return null;
    }

    const clamped = Math.min(parsedSeconds, MAX_SIMPLE_CALL_DURATION_SECONDS);
    return clamped * 1000;
  };

  const parseServerCommandDurationMs = (durationSeconds?: number | null): number | null => {
    if (typeof durationSeconds !== "number" || !Number.isFinite(durationSeconds) || durationSeconds <= 0) {
      return null;
    }

    const clamped = Math.min(durationSeconds, MAX_SIMPLE_CALL_DURATION_SECONDS);
    return Math.round(clamped * 1000);
  };

  const executeOutgoingCall = async (
    rawPhoneNumber: string,
    source: "server" | "manual",
    commandId?: string,
    commandDurationSeconds?: number | null
  ): Promise<boolean> => {
    const normalized = normalizePhoneNumber(rawPhoneNumber);
    if (!normalized) {
      setStatusMessage("Invalid phone number");
      if (commandId) {
        await updateCommandStatus(commandId, "failed");
      }
      return false;
    }

    try {
      if (commandId) {
        inFlightCommandIds.current.add(commandId);
        await updateCommandStatus(commandId, "executing");
      }

      const hasPermissions = await requestAndroidPermissions();
      if (!hasPermissions) {
        if (commandId) {
          await updateCommandStatus(commandId, "failed");
        }
        return false;
      }

      if (source === "server") {
        logEvent("Outgoing call command received", {
          phoneNumber: normalized,
          commandId,
          durationSeconds: commandDurationSeconds ?? null,
        });
      }

      const autoEndMs =
        source === "server"
          ? parseServerCommandDurationMs(commandDurationSeconds)
          : parseSimpleCallDurationMs();
      const callResult = await startSimpleCall(normalized, autoEndMs);
      if (!callResult.success) {
        setStatusMessage(`Call rejected: ${callResult.message}`);
        if (commandId) {
          await updateCommandStatus(commandId, "failed");
        }
        return false;
      }
      await refreshStatus();

      if (commandId) {
        await updateCommandStatus(commandId, "executed");
      }

      setStatusMessage(source === "server" ? "Outgoing call started from server command" : "Outgoing call started");
      return true;
    } catch (error) {
      logEvent("execute_outgoing_call_failed", { error, source, commandId, rawPhoneNumber });
      if (commandId) {
        await updateCommandStatus(commandId, "failed");
      }
      setStatusMessage("Failed to start outgoing call");
      return false;
    } finally {
      if (commandId) {
        inFlightCommandIds.current.delete(commandId);
      }
    }
  };

  const executeEndCommand = async (commandId: string): Promise<boolean> => {
    try {
      inFlightCommandIds.current.add(commandId);
      await updateCommandStatus(commandId, "executing");
      console.log("End command received");
      const result = await endCurrentCall();
      await refreshStatus();

      if (result.ended) {
        await updateCommandStatus(commandId, "executed");
        setStatusMessage("Current call ended from server command");
        return true;
      }

      await updateCommandStatus(commandId, "failed");
      setStatusMessage("Could not end current call from server command");
      return false;
    } catch (error) {
      logEvent("execute_end_command_failed", { error, commandId });
      await updateCommandStatus(commandId, "failed");
      setStatusMessage("Failed to execute end command");
      return false;
    } finally {
      inFlightCommandIds.current.delete(commandId);
    }
  };

  const getScheduledAtMs = (command: ServerCallCommand): number => {
    if (!command.scheduledAt) return 0;
    const dateValue = new Date(command.scheduledAt).getTime();
    return Number.isNaN(dateValue) ? 0 : dateValue;
  };

  const pollCommands = async () => {
    try {
      const response = await fetch(`${SERVER}/commands?deviceUid=${DEVICE_UID}&status=pending`);
      if (!response.ok) return;

      const commands = (await response.json()) as ServerCallCommand[];
      const dueCommands = [...commands].sort((a, b) => getScheduledAtMs(a) - getScheduledAtMs(b));

      setNextServerCommand(dueCommands.find((command) => command.type === "CALL") ?? null);

      for (const command of dueCommands) {
        const dueNow = !command.scheduledAt || Date.now() >= getScheduledAtMs(command);
        if (!dueNow) continue;
        if (inFlightCommandIds.current.has(command.id)) continue;

        const action =
          command.action ??
          (command.type === "END" ? "end" : "call");

        if (action === "end") {
          await executeEndCommand(command.id);
          continue;
        }

        if (!command.phoneNumber) {
          await updateCommandStatus(command.id, "failed");
          continue;
        }

        await executeOutgoingCall(
          command.phoneNumber,
          "server",
          command.id,
          command.durationSeconds ?? null
        );
      }
    } catch (error) {
      logEvent("poll_commands_failed", { error });
    }
  };

  const onToggleAutoAnswer = async (enabled: boolean) => {
    try {
      const hasPermissions = await requestAndroidPermissions();
      if (!hasPermissions) return;

      if (enabled) {
        const parsed = Number.parseInt(autoHangupSecondsText, 10);
        const seconds = Number.isNaN(parsed) ? DEFAULT_HANGUP_SECONDS : parsed;
        const status = await enableAutoAnswer(seconds);
        updateUiFromStatus(status);
        setStatusMessage(`Auto Answer ON (${status.autoHangupSeconds}s)`);
      } else {
        const status = await disableAutoAnswer();
        updateUiFromStatus(status);
        setStatusMessage("Auto Answer OFF");
      }
    } catch (error) {
      logEvent("toggle_auto_answer_failed", { error, enabled });
      setStatusMessage("Failed to change auto-answer mode");
    }
  };

  const onApplyHangupSeconds = async () => {
    if (!uiState.autoAnswerEnabled) {
      setStatusMessage("Turn on Auto Answer first");
      return;
    }

    const parsed = Number.parseInt(autoHangupSecondsText, 10);
    const seconds = Number.isNaN(parsed) ? DEFAULT_HANGUP_SECONDS : parsed;

    try {
      const status = await enableAutoAnswer(seconds);
      updateUiFromStatus(status);
      setStatusMessage(`Auto hangup set to ${status.autoHangupSeconds}s`);
    } catch (error) {
      logEvent("apply_hangup_seconds_failed", { error, seconds });
      setStatusMessage("Failed to update hangup seconds");
    }
  };

  const onCallNow = async () => {
    await executeOutgoingCall(phoneNumber, "manual");
  };

  const onEndCurrentCall = async () => {
    try {
      const result = await endCurrentCall();
      await refreshStatus();
      setStatusMessage(result.ended ? "Current call ended" : "Could not end current call");
    } catch (error) {
      logEvent("end_current_call_failed", { error });
      setStatusMessage("Failed to end current call");
    }
  };

  useEffect(() => {
    let heartbeatTimer: ReturnType<typeof setInterval>;
    let pollTimer: ReturnType<typeof setInterval>;
    let statusTimer: ReturnType<typeof setInterval>;

    const init = async () => {
      await requestAndroidPermissions();
      await refreshStatus();
      await registerDevice();
      await pollCommands();

      heartbeatTimer = setInterval(() => {
        void sendHeartbeat();
      }, POLL_INTERVAL_MS);

      pollTimer = setInterval(() => {
        void pollCommands();
      }, POLL_INTERVAL_MS);

      statusTimer = setInterval(() => {
        void refreshStatus();
      }, 3000);
    };

    void init();

    return () => {
      if (heartbeatTimer) clearInterval(heartbeatTimer);
      if (pollTimer) clearInterval(pollTimer);
      if (statusTimer) clearInterval(statusTimer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <View style={styles.screen}>
      <View style={styles.card}>
        <Text style={styles.title}>AutoCall</Text>
        <Text style={styles.subtitle}>Personal Android Call Control</Text>

        <View style={styles.section}>
          <View style={styles.rowBetween}>
            <Text style={styles.label}>Auto Answer</Text>
            <Switch value={uiState.autoAnswerEnabled} onValueChange={onToggleAutoAnswer} />
          </View>

          <Text style={styles.label}>Auto Hangup Seconds</Text>
          <View style={styles.row}>
            <TextInput
              value={autoHangupSecondsText}
              onChangeText={setAutoHangupSecondsText}
              keyboardType="number-pad"
              style={[styles.input, styles.secondsInput]}
              placeholder="20"
              placeholderTextColor="#8ca0bf"
            />
            <Pressable style={styles.secondaryButton} onPress={onApplyHangupSeconds}>
              <Text style={styles.secondaryButtonText}>Apply</Text>
            </Pressable>
          </View>
        </View>

        <View style={styles.section}>
          <Text style={styles.label}>Phone Number</Text>
          <TextInput
            value={phoneNumber}
            onChangeText={setPhoneNumber}
            keyboardType="phone-pad"
            autoCorrect={false}
            autoCapitalize="none"
            selectionColor="#ffffff"
            cursorColor="#ffffff"
            underlineColorAndroid="transparent"
            style={[styles.input, styles.fullInput, styles.visibleInput]}
            placeholder="05xxxxxxxx"
            placeholderTextColor="#8ca0bf"
          />
          <Text style={[styles.label, styles.durationLabel]}>Call Duration (seconds)</Text>
          <TextInput
            value={simpleCallDurationSecondsText}
            onChangeText={setSimpleCallDurationSecondsText}
            keyboardType="number-pad"
            autoCorrect={false}
            autoCapitalize="none"
            selectionColor="#ffffff"
            cursorColor="#ffffff"
            underlineColorAndroid="transparent"
            style={[styles.input, styles.fullInput, styles.visibleInput]}
            placeholder="5"
            placeholderTextColor="#8ca0bf"
          />
          <Text style={styles.helperText}>Call duration starts from the moment the call begins.</Text>

          <View style={styles.row}>
            <Pressable style={styles.primaryButton} onPress={onCallNow}>
              <Text style={styles.primaryButtonText}>Call Now</Text>
            </Pressable>
            <Pressable style={styles.dangerButton} onPress={onEndCurrentCall}>
              <Text style={styles.dangerButtonText}>End Current Call</Text>
            </Pressable>
          </View>
        </View>

        <View style={styles.statusBox}>
          <Text style={styles.statusLine}>Auto Answer: {uiState.autoAnswerEnabled ? "ON" : "OFF"}</Text>
          <Text style={styles.statusLine}>Auto Hangup: {uiState.autoHangupSeconds}s</Text>
          <Text style={styles.statusLine}>Hangup Timer: {uiState.hangupScheduled ? "Scheduled" : "Idle"}</Text>
          <Text style={styles.statusLine}>Last Event: {uiState.lastEvent || "--"}</Text>
          <Text style={styles.statusLine}>Event Time: {formatTime(uiState.lastEventAt)}</Text>
          <Text style={styles.statusLine}>
            Next Server Call: {nextServerCommand?.phoneNumber ?? "No pending calls"}
          </Text>
          <Text style={styles.statusMessage}>{statusMessage}</Text>
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: "#0f1627",
    justifyContent: "center",
    alignItems: "center",
    padding: 16,
  },
  card: {
    width: "100%",
    maxWidth: 460,
    backgroundColor: "#1b2740",
    borderRadius: 16,
    padding: 16,
    borderWidth: 1,
    borderColor: "#324869",
  },
  title: {
    fontSize: 26,
    fontWeight: "700",
    color: "#ffffff",
  },
  subtitle: {
    marginTop: 4,
    marginBottom: 12,
    fontSize: 14,
    color: "#b8c7de",
  },
  section: {
    marginTop: 12,
    padding: 12,
    borderRadius: 12,
    backgroundColor: "#152037",
    borderWidth: 1,
    borderColor: "#273a58",
  },
  rowBetween: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 8,
  },
  row: {
    flexDirection: "row",
    gap: 8,
    alignItems: "center",
    marginTop: 12,
  },
  label: {
    fontSize: 14,
    fontWeight: "600",
    color: "#dde7f8",
  },
  durationLabel: {
    marginTop: 10,
  },
  helperText: {
    marginTop: 6,
    fontSize: 12,
    color: "#9eb0cc",
    lineHeight: 18,
  },
  input: {
    borderWidth: 1,
    borderColor: "#34507a",
    borderRadius: 10,
    paddingHorizontal: 10,
    paddingVertical: 9,
    color: "#ffffff",
    backgroundColor: "#101a2d",
  },
  fullInput: {
    width: "100%",
  },
  visibleInput: {
    minHeight: 46,
    paddingVertical: 10,
    fontSize: 18,
    textAlignVertical: "center",
    textAlign: "left",
  },
  secondsInput: {
    width: 120,
    flexShrink: 0,
  },
  primaryButton: {
    flex: 1,
    borderRadius: 10,
    backgroundColor: "#27c596",
    paddingVertical: 10,
    alignItems: "center",
  },
  primaryButtonText: {
    color: "#062019",
    fontWeight: "700",
  },
  secondaryButton: {
    borderRadius: 10,
    backgroundColor: "#89a7ff",
    paddingVertical: 10,
    paddingHorizontal: 14,
    alignItems: "center",
  },
  secondaryButtonText: {
    color: "#101d3a",
    fontWeight: "700",
  },
  dangerButton: {
    flex: 1,
    borderRadius: 10,
    backgroundColor: "#e25f7c",
    paddingVertical: 10,
    alignItems: "center",
  },
  dangerButtonText: {
    color: "#2b0814",
    fontWeight: "700",
  },
  statusBox: {
    marginTop: 12,
    borderRadius: 12,
    backgroundColor: "#101b2f",
    borderWidth: 1,
    borderColor: "#273a58",
    padding: 12,
  },
  statusLine: {
    color: "#e6efff",
    fontSize: 13,
    marginBottom: 4,
  },
  statusMessage: {
    color: "#7fe3ca",
    fontSize: 13,
    marginTop: 8,
    fontWeight: "600",
  },
});

