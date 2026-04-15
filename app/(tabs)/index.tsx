import { useEffect, useState } from "react";
import {
  NativeModules,
  Platform,
  PermissionsAndroid,
  StyleSheet,
  Text,
  View,
} from "react-native";

const SERVER = "https://serverautocall-production.up.railway.app";
const deviceUid = "device_123";

type CallCommand = {
  id: string;
  deviceUid: string;
  type: "CALL";
  phoneNumber: string;
  status: string;
  scheduledAt?: string;
};

export default function Index() {
  const [nextCall, setNextCall] = useState<CallCommand | null>(null);

  const requestCallPermission = async (): Promise<boolean> => {
    if (Platform.OS !== "android") return true;

    const hasPermission = await PermissionsAndroid.check(
      PermissionsAndroid.PERMISSIONS.CALL_PHONE
    );
    if (hasPermission) return true;

    const result = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.CALL_PHONE,
      {
        title: "Phone Call Permission",
        message: "AutoCall needs permission to make phone calls",
        buttonPositive: "OK",
      }
    );

    return result === PermissionsAndroid.RESULTS.GRANTED;
  };

  const getScheduledTime = (command: CallCommand) => {
    if (!command.scheduledAt) return 0; // ���� ��� = ����
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
    await fetch(`${SERVER}/devices/register`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceUid }),
    });
  };

  const heartbeat = async () => {
    await fetch(`${SERVER}/devices/heartbeat`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceUid }),
    });
  };

  const makeCall = async (phoneNumber: string, id: string) => {
    const hasPermission = await requestCallPermission();
    if (!hasPermission) {
      console.log("Permission denied");
      return;
    }

    try {
      await fetch(`${SERVER}/commands/${id}/status`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ status: "executing" }),
      });

      const { DirectCall } = NativeModules;
      DirectCall.call(phoneNumber);

      await fetch(`${SERVER}/commands/${id}/status`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ status: "executed" }),
      });
    } catch {
      await fetch(`${SERVER}/commands/${id}/status`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ status: "failed" }),
      });
    }
  };

  const poll = async () => {
    const res = await fetch(
      `${SERVER}/commands?deviceUid=${deviceUid}&status=pending`
    );

    const data = (await res.json()) as CallCommand[];

    const callCommands = data
      .filter((cmd) => cmd.type === "CALL")
      .sort((a, b) => getScheduledTime(a) - getScheduledTime(b));

    if (callCommands.length === 0) {
      setNextCall(null);
      return;
    }

    setNextCall(callCommands[0]);

    callCommands.forEach((cmd) => {
      const scheduledTime = getScheduledTime(cmd);
      const shouldRunNow = !cmd.scheduledAt || Date.now() >= scheduledTime;

      if (shouldRunNow) {
        makeCall(cmd.phoneNumber, cmd.id);
      }
    });
  };

  useEffect(() => {
    let hb: ReturnType<typeof setInterval>;
    let pl: ReturnType<typeof setInterval>;

    const init = async () => {
      await requestCallPermission();

      await register();
      await poll();

      hb = setInterval(heartbeat, 10000);
      pl = setInterval(poll, 10000);
    };

    init();

    return () => {
      if (hb) clearInterval(hb);
      if (pl) clearInterval(pl);
    };
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
});
