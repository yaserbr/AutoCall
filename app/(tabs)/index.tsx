import { useEffect } from "react";
import { NativeModules, PermissionsAndroid, Text, View } from "react-native";

const SERVER = "http://192.168.1.111:4000"; // ← غيرها

const deviceUid = "device_123";

export default function Index() {

  const register = async () => {
    await fetch(`${SERVER}/devices/register`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceUid })
    });
  };

  const heartbeat = async () => {
    await fetch(`${SERVER}/devices/heartbeat`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceUid })
    });
  };

  const makeCall = async (phoneNumber: string, id: string) => {
    try {
      await fetch(`${SERVER}/commands/${id}/status`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ status: "executing" })
      });


      const { DirectCall } = NativeModules;

      DirectCall.call(phoneNumber);

      await fetch(`${SERVER}/commands/${id}/status`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ status: "executed" })
      });

    } catch {
      await fetch(`${SERVER}/commands/${id}/status`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ status: "failed" })
      });
    }
  };

  const poll = async () => {
    const res = await fetch(
      `${SERVER}/commands?deviceUid=${deviceUid}&status=pending`
    );
    const data = await res.json();

    data.forEach((cmd: any) => {
      if (cmd.type === "CALL") {
        makeCall(cmd.phoneNumber, cmd.id);
      }
    });
  };

  useEffect(() => {
    let hb: ReturnType<typeof setInterval>;
    let pl: ReturnType<typeof setInterval>;

    const init = async () => {
      await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.CALL_PHONE
      );

      await register();

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
    <View style={{ flex: 1, justifyContent: "center", alignItems: "center" }}>
      <Text>📞 AutoCall Running</Text>
    </View>
  );
}