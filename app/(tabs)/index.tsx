import { useEffect, useRef, useState } from "react";
import {
  AppState,
  NativeModules,
  PermissionsAndroid,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";

const SERVER = "https://serverautocall-production.up.railway.app";
const deviceUid = "device_123";
const LOG_PREFIX = "[AutoCall]";
const DEFAULT_TEST_PHONE = "+15555550123";

type CallCommand = {
  id: string;
  deviceUid: string;
  type: "CALL";
  phoneNumber: string;
  status: string;
  scheduledAt?: string;
};

type DirectCallResult = {
  action: string;
  phoneNumber: string;
  usedCurrentActivity: boolean;
  timestamp: string;
};

type DirectCallNativeModule = {
  call(phoneNumber: string): Promise<DirectCallResult>;
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

  const onlyDigits = normalized.replace(/\+/g, "");
  if (!onlyDigits) return null;

  return normalized;
};

export default function Index() {
  const [nextCall, setNextCall] = useState<CallCommand | null>(null);
  const [testPhoneNumber, setTestPhoneNumber] = useState(DEFAULT_TEST_PHONE);
  const inFlightCommandIds = useRef<Set<string>>(new Set());

  const requestCallPermission = async (source: string): Promise<boolean> => {
    if (Platform.OS !== "android") return true;

    try {
      const hasPermission = await PermissionsAndroid.check(
        PermissionsAndroid.PERMISSIONS.CALL_PHONE
      );
      logEvent("runtime_permission_check", { source, hasPermission });

      if (hasPermission) return true;

      const result = await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.CALL_PHONE,
        {
          title: "Phone Call Permission",
          message: "AutoCall needs permission to make phone calls",
          buttonPositive: "OK",
          buttonNegative: "Cancel",
        }
      );

      const granted = result === PermissionsAndroid.RESULTS.GRANTED;
      logEvent("runtime_permission_request_result", {
        source,
        result,
        granted,
      });
      return granted;
    } catch (error) {
      logEvent("runtime_permission_error", { source, error });
      return false;
    }
  };

  const getScheduledTime = (command: CallCommand) => {
    if (!command.scheduledAt) return 0;
    const time = new Date(command.scheduledAt).getTime();
    return Number.isNaN(time) ? 0 : time;
  };

  const formatCallTime = (scheduledAt?: string) => {
    if (!scheduledAt) return "Now";
    const time = new Date(scheduledAt);
    if (Number.isNaN(time.getTime())) return "Now";
    return time.toLocaleString();
  };

  const register = async () => {
    logEvent("register_start", { deviceUid });
    const response = await fetch(`${SERVER}/devices/register`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceUid }),
    });
    logEvent("register_done", { status: response.status });
  };

  const heartbeat = async () => {
    try {
      const response = await fetch(`${SERVER}/devices/heartbeat`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ deviceUid }),
      });
      logEvent("heartbeat_done", { status: response.status });
    } catch (error) {
      logEvent("heartbeat_error", { error });
    }
  };

  const updateCommandStatus = async (id: string, status: string) => {
    try {
      const response = await fetch(`${SERVER}/commands/${id}/status`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ status }),
      });
      logEvent("command_status_update", { id, status, http: response.status });
    } catch (error) {
      logEvent("command_status_update_error", { id, status, error });
    }
  };

  const getDirectCallModule = (): DirectCallNativeModule | null => {
    const moduleCandidate = (NativeModules as { DirectCall?: unknown }).DirectCall;

    if (!moduleCandidate) {
      logEvent("native_module_missing", { hasDirectCall: false });
      return null;
    }

    const directCall = moduleCandidate as Partial<DirectCallNativeModule>;
    if (typeof directCall.call !== "function") {
      logEvent("native_module_invalid_shape", { hasCallMethod: false });
      return null;
    }

    return directCall as DirectCallNativeModule;
  };

  const executeDirectCall = async (
    rawPhoneNumber: string,
    source: "server-command" | "manual-debug",
    commandId?: string
  ): Promise<boolean> => {
    logEvent("call_flow_entered", {
      source,
      commandId,
      rawPhoneNumber,
      appState: AppState.currentState,
    });

    const phoneNumber = normalizePhoneNumber(rawPhoneNumber);
    if (!phoneNumber) {
      logEvent("call_flow_invalid_number", { source, commandId, rawPhoneNumber });
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

      const hasPermission = await requestCallPermission(source);
      if (!hasPermission) {
        logEvent("call_flow_permission_denied", { source, commandId, phoneNumber });
        if (commandId) {
          await updateCommandStatus(commandId, "failed");
        }
        return false;
      }

      const directCall = getDirectCallModule();
      if (!directCall) {
        if (commandId) {
          await updateCommandStatus(commandId, "failed");
        }
        return false;
      }

      logEvent("native_call_invocation_start", { source, commandId, phoneNumber });
      const nativeResult = await directCall.call(phoneNumber);
      logEvent("native_call_invocation_success", {
        source,
        commandId,
        phoneNumber,
        nativeResult,
      });

      if (commandId) {
        await updateCommandStatus(commandId, "executed");
      }
      return true;
    } catch (error) {
      logEvent("native_call_invocation_error", {
        source,
        commandId,
        phoneNumber,
        error,
      });
      if (commandId) {
        await updateCommandStatus(commandId, "failed");
      }
      return false;
    } finally {
      if (commandId) {
        inFlightCommandIds.current.delete(commandId);
      }
    }
  };

  const poll = async () => {
    try {
      const response = await fetch(
        `${SERVER}/commands?deviceUid=${deviceUid}&status=pending`
      );
      logEvent("poll_response", { status: response.status });

      if (!response.ok) {
        return;
      }

      const data = (await response.json()) as CallCommand[];

      const callCommands = data
        .filter((cmd) => cmd.type === "CALL")
        .sort((a, b) => getScheduledTime(a) - getScheduledTime(b));

      if (callCommands.length === 0) {
        setNextCall(null);
        return;
      }

      setNextCall(callCommands[0]);
      logEvent("server_command_received", {
        count: callCommands.length,
        nextCommand: callCommands[0],
      });

      callCommands.forEach((cmd) => {
        const scheduledTime = getScheduledTime(cmd);
        const shouldRunNow = !cmd.scheduledAt || Date.now() >= scheduledTime;

        if (!shouldRunNow) return;
        if (inFlightCommandIds.current.has(cmd.id)) {
          logEvent("server_command_skipped_inflight", { commandId: cmd.id });
          return;
        }

        void executeDirectCall(cmd.phoneNumber, "server-command", cmd.id);
      });
    } catch (error) {
      logEvent("poll_error", { error });
    }
  };

  useEffect(() => {
    let hb: ReturnType<typeof setInterval>;
    let pl: ReturnType<typeof setInterval>;

    const init = async () => {
      logEvent("app_init_start");
      await requestCallPermission("startup");

      try {
        await register();
      } catch (error) {
        logEvent("register_error", { error });
      }

      await poll();

      hb = setInterval(() => {
        void heartbeat();
      }, 10000);
      pl = setInterval(() => {
        void poll();
      }, 10000);

      logEvent("app_init_done");
    };

    void init();

    return () => {
      logEvent("app_cleanup");
      if (hb) clearInterval(hb);
      if (pl) clearInterval(pl);
    };
    // Intentional one-time startup flow for device registration/poll timers.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <View style={styles.screen}>
      <View style={styles.bgShapeOne} />
      <View style={styles.bgShapeTwo} />

      <View style={styles.glassCard}>
        <View style={styles.statusRow}>
          <View style={styles.dot} />
          <Text style={styles.statusLabel}>AutoCall Running</Text>
        </View>

        <Text style={styles.title}>Direct Call Agent</Text>
        <Text style={styles.subtitle}>
          Device is connected and waiting for pending call commands.
        </Text>

        <View style={styles.metaBox}>
          <Text style={styles.metaLabel}>Device UID</Text>
          <Text style={styles.metaValue}>{deviceUid}</Text>
        </View>

        <View style={styles.nextCallBox}>
          <Text style={styles.nextCallTitle}>Next Call</Text>
          <Text style={styles.nextCallNumber}>
            {nextCall?.phoneNumber ?? "No pending calls"}
          </Text>
          <Text style={styles.nextCallTime}>
            {nextCall ? formatCallTime(nextCall.scheduledAt) : "Now"}
          </Text>
        </View>

        <View style={styles.testBox}>
          <Text style={styles.nextCallTitle}>Manual Foreground Test</Text>
          <TextInput
            value={testPhoneNumber}
            onChangeText={setTestPhoneNumber}
            placeholder="+15555550123"
            placeholderTextColor="rgba(215, 229, 252, 0.58)"
            keyboardType="phone-pad"
            style={styles.input}
          />
          <Pressable
            style={styles.callButton}
            onPress={() => {
              void executeDirectCall(testPhoneNumber, "manual-debug");
            }}>
            <Text style={styles.callButtonText}>Trigger Direct Call</Text>
          </Pressable>
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: "#0d1324",
    justifyContent: "center",
    alignItems: "center",
    padding: 20,
    overflow: "hidden",
  },
  bgShapeOne: {
    position: "absolute",
    width: 240,
    height: 240,
    borderRadius: 999,
    backgroundColor: "rgba(82, 159, 255, 0.24)",
    top: 90,
    left: -40,
  },
  bgShapeTwo: {
    position: "absolute",
    width: 260,
    height: 260,
    borderRadius: 999,
    backgroundColor: "rgba(56, 231, 201, 0.20)",
    bottom: 70,
    right: -60,
  },
  glassCard: {
    width: "100%",
    maxWidth: 430,
    borderRadius: 24,
    borderWidth: 1,
    borderColor: "rgba(255, 255, 255, 0.28)",
    backgroundColor: "rgba(255, 255, 255, 0.09)",
    padding: 22,
    shadowColor: "#000",
    shadowOpacity: 0.26,
    shadowRadius: 24,
    shadowOffset: { width: 0, height: 14 },
    elevation: 10,
  },
  statusRow: {
    flexDirection: "row",
    alignItems: "center",
    marginBottom: 14,
  },
  dot: {
    width: 10,
    height: 10,
    borderRadius: 999,
    backgroundColor: "#35e3c5",
    marginRight: 8,
  },
  statusLabel: {
    fontSize: 13,
    fontWeight: "700",
    color: "#d8fff8",
    letterSpacing: 0.5,
    textTransform: "uppercase",
  },
  title: {
    color: "#ffffff",
    fontSize: 28,
    fontWeight: "800",
    marginBottom: 8,
  },
  subtitle: {
    color: "rgba(233, 241, 255, 0.9)",
    fontSize: 15,
    lineHeight: 23,
    marginBottom: 18,
  },
  metaBox: {
    borderRadius: 14,
    borderWidth: 1,
    borderColor: "rgba(255, 255, 255, 0.24)",
    backgroundColor: "rgba(7, 16, 36, 0.35)",
    paddingVertical: 12,
    paddingHorizontal: 14,
  },
  metaLabel: {
    color: "rgba(207, 222, 252, 0.86)",
    fontSize: 12,
    marginBottom: 4,
    letterSpacing: 0.4,
    textTransform: "uppercase",
  },
  metaValue: {
    color: "#ffffff",
    fontSize: 16,
    fontWeight: "600",
  },
  nextCallBox: {
    marginTop: 14,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: "rgba(255, 255, 255, 0.2)",
    backgroundColor: "rgba(255, 255, 255, 0.06)",
    paddingVertical: 12,
    paddingHorizontal: 14,
  },
  nextCallTitle: {
    color: "rgba(207, 222, 252, 0.9)",
    fontSize: 12,
    marginBottom: 6,
    letterSpacing: 0.4,
    textTransform: "uppercase",
  },
  nextCallNumber: {
    color: "#ffffff",
    fontSize: 20,
    fontWeight: "700",
    marginBottom: 4,
  },
  nextCallTime: {
    color: "rgba(233, 241, 255, 0.9)",
    fontSize: 14,
    fontWeight: "500",
  },
  testBox: {
    marginTop: 14,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: "rgba(95, 246, 202, 0.35)",
    backgroundColor: "rgba(7, 16, 36, 0.42)",
    paddingVertical: 12,
    paddingHorizontal: 14,
  },
  input: {
    marginTop: 6,
    borderWidth: 1,
    borderColor: "rgba(255, 255, 255, 0.26)",
    borderRadius: 10,
    backgroundColor: "rgba(255, 255, 255, 0.08)",
    color: "#ffffff",
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 16,
    fontWeight: "600",
  },
  callButton: {
    marginTop: 10,
    borderRadius: 10,
    backgroundColor: "#3ee5c8",
    paddingVertical: 11,
    alignItems: "center",
  },
  callButtonText: {
    color: "#06211d",
    fontSize: 15,
    fontWeight: "800",
    letterSpacing: 0.25,
  },
});
