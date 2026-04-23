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
  type DeviceIdentity,
  type InAppWebViewState,
  closeInAppWebView,
  disableAutoAnswer,
  getDeviceIdentity,
  getInAppWebViewState,
  enableAutoAnswer,
  endCurrentCall,
  getStatus,
  openInstalledApp,
  openInAppWebView,
  returnToAutoCall,
  startSimpleCall,
  startServerCommandCall,
  startServerCommandSms,
} from "../src/native/autoCallNative";

const SERVER = "https://serverautocall-production.up.railway.app";
const POLL_INTERVAL_MS = 10000;
const DEFAULT_PHONE = "05";
const DEFAULT_HANGUP_SECONDS = 20;
const DEFAULT_SIMPLE_CALL_DURATION_SECONDS = 20;
const MAX_AUTO_HANGUP_SECONDS = 600;
const MAX_SIMPLE_CALL_DURATION_SECONDS = 3600;
const MAX_TRACKED_PROCESSED_COMMAND_IDS = 500;
const LOG_PREFIX = "[AutoCall/UI]";
const SMS_PERMISSION_DENIED_REASON = "SEND_SMS permission denied";
const DEVICE_UID_REGEX = /^[a-z0-9]{5}$/;

type ServerCallCommand = {
  id: string;
  deviceUid: string;
  action?:
    | "call"
    | "end"
    | "sms"
    | "auto_answer"
    | "open_url"
    | "close_webview"
    | "open_app"
    | "return_to_autocall";
  type:
    | "CALL"
    | "END"
    | "SMS"
    | "AUTO_ANSWER"
    | "OPEN_URL"
    | "CLOSE_WEBVIEW"
    | "OPEN_APP"
    | "RETURN_TO_AUTOCALL";
  phoneNumber?: string | null;
  message?: string | null;
  url?: string | null;
  appName?: string | null;
  resolvedPackageName?: string | null;
  durationSeconds?: number | null;
  enabled?: boolean | null;
  autoHangupSeconds?: number | null;
  failureReason?: string | null;
  status: string;
  scheduledAt?: string;
};

type ServerDevice = {
  deviceUid?: string;
  deviceName?: string | null;
};

type ClaimNextCommandResponse = {
  success?: boolean;
  command?: ServerCallCommand | null;
};

type UiState = {
  autoAnswerEnabled: boolean;
  autoHangupSeconds: number;
  hangupScheduled: boolean;
  lastEvent: string;
  lastEventAt: number;
};
type AndroidPermission =
  (typeof PermissionsAndroid.PERMISSIONS)[keyof typeof PermissionsAndroid.PERMISSIONS];
type ServerCommandAction =
  | "call"
  | "end"
  | "sms"
  | "auto_answer"
  | "open_url"
  | "close_webview"
  | "open_app"
  | "return_to_autocall";

const emptyState: UiState = {
  autoAnswerEnabled: false,
  autoHangupSeconds: DEFAULT_HANGUP_SECONDS,
  hangupScheduled: false,
  lastEvent: "Idle",
  lastEventAt: 0,
};

const emptyInAppWebViewState: InAppWebViewState = {
  isOpen: false,
  currentUrl: null,
};

const logEvent = (event: string, payload?: unknown) => {
  if (payload !== undefined) {
    console.log(`${LOG_PREFIX} ${event}`, payload);
    return;
  }
  console.log(`${LOG_PREFIX} ${event}`);
};

const normalizeDeviceUid = (value: string | null | undefined): string => {
  if (typeof value !== "string") {
    return "";
  }
  const normalized = value.trim().toLowerCase();
  return DEVICE_UID_REGEX.test(normalized) ? normalized : "";
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

const normalizeHttpUrl = (input: string | null | undefined): string | null => {
  if (typeof input !== "string") {
    return null;
  }
  const trimmed = input.trim();
  if (!trimmed) {
    return null;
  }
  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
      return null;
    }
    return parsed.toString();
  } catch {
    return null;
  }
};

const normalizeAppName = (input: string | null | undefined): string | null => {
  if (typeof input !== "string") {
    return null;
  }
  const normalized = input.trim().replace(/\s+/g, " ");
  return normalized || null;
};

const formatTime = (timestamp: number): string => {
  if (!timestamp) return "--";
  return new Date(timestamp).toLocaleString();
};

const emptyDeviceIdentity: DeviceIdentity = {
  deviceUid: "",
  deviceName: "",
};

export default function AutoCallScreen() {
  const [uiState, setUiState] = useState<UiState>(emptyState);
  const [deviceIdentity, setDeviceIdentity] = useState<DeviceIdentity>(emptyDeviceIdentity);
  const [statusMessage, setStatusMessage] = useState("Ready");
  const [phoneNumber, setPhoneNumber] = useState(DEFAULT_PHONE);
  const [autoHangupSecondsText, setAutoHangupSecondsText] = useState(String(DEFAULT_HANGUP_SECONDS));
  const [simpleCallDurationSecondsText, setSimpleCallDurationSecondsText] = useState(
    String(DEFAULT_SIMPLE_CALL_DURATION_SECONDS)
  );
  const [nextServerCommand, setNextServerCommand] = useState<ServerCallCommand | null>(null);
  const [webViewState, setWebViewState] = useState<InAppWebViewState>(emptyInAppWebViewState);
  const inFlightCommandIds = useRef<Set<string>>(new Set());
  const processedCommandIds = useRef<Set<string>>(new Set());
  const pollInProgressRef = useRef(false);
  const deviceUidRef = useRef<string>("");
  const deviceNameRef = useRef<string>("");

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

  const applyDeviceIdentity = (identity: DeviceIdentity) => {
    const normalizedUid = normalizeDeviceUid(identity.deviceUid);
    const normalizedName = typeof identity.deviceName === "string" ? identity.deviceName.trim() : "";

    deviceUidRef.current = normalizedUid;
    deviceNameRef.current = normalizedName;
    setDeviceIdentity({
      deviceUid: normalizedUid,
      deviceName: normalizedName,
    });
  };

  const refreshDeviceIdentity = async (): Promise<DeviceIdentity | null> => {
    try {
      const identity = await getDeviceIdentity();
      applyDeviceIdentity(identity);
      return identity;
    } catch (error) {
      logEvent("device_identity_refresh_failed", { error });
      return null;
    }
  };

  const getCurrentDeviceUid = async (): Promise<string | null> => {
    const cachedUid = normalizeDeviceUid(deviceUidRef.current);
    if (cachedUid) {
      deviceUidRef.current = cachedUid;
      return cachedUid;
    }

    const identity = await refreshDeviceIdentity();
    const normalizedUid = normalizeDeviceUid(identity?.deviceUid);
    return normalizedUid || null;
  };

  const applyDeviceFromServer = (device?: ServerDevice | null) => {
    if (!device || typeof device !== "object") {
      return;
    }

    const serverUid = normalizeDeviceUid(device.deviceUid);
    if (!serverUid) {
      return;
    }

    const currentUid = normalizeDeviceUid(deviceUidRef.current);
    if (!currentUid || serverUid !== currentUid) {
      return;
    }

    const serverName =
      typeof device.deviceName === "string" && device.deviceName.trim()
        ? device.deviceName.trim()
        : "";
    if (!serverName) {
      return;
    }

    deviceUidRef.current = currentUid;
    deviceNameRef.current = serverName;
    setDeviceIdentity(() => ({
      deviceUid: currentUid,
      deviceName: serverName,
    }));
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

  const refreshInAppWebViewState = async () => {
    try {
      const snapshot = await getInAppWebViewState();
      setWebViewState(snapshot);
      logEvent("webview_state_refresh", snapshot);
    } catch (error) {
      logEvent("webview_state_refresh_failed", { error });
    }
  };

  const requestAndroidPermissions = async (
    requiredPermissions?: AndroidPermission[]
  ): Promise<boolean> => {
    if (Platform.OS !== "android") return true;

    const permissions =
      requiredPermissions ??
      [
        PermissionsAndroid.PERMISSIONS.CALL_PHONE,
        PermissionsAndroid.PERMISSIONS.ANSWER_PHONE_CALLS,
        PermissionsAndroid.PERMISSIONS.READ_PHONE_STATE,
      ];

    for (const permission of permissions) {
      const isGranted = await PermissionsAndroid.check(permission);
      if (isGranted) {
        continue;
      }

      const granted = await PermissionsAndroid.request(permission);
      if (granted !== PermissionsAndroid.RESULTS.GRANTED) {
        setStatusMessage(`Permission denied: ${permission}`);
        return false;
      }
    }

    if (Platform.Version >= 33) {
      const hasNotificationPermission = await PermissionsAndroid.check(
        PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
      );
      if (!hasNotificationPermission) {
        await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
      }
    }

    return true;
  };

  const registerDevice = async () => {
    try {
      const deviceUid = await getCurrentDeviceUid();
      if (!deviceUid) {
        logEvent("register_device_skipped_missing_uid");
        return;
      }

      const response = await fetch(`${SERVER}/devices/register`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ deviceUid }),
      });

      if (!response.ok) {
        return;
      }

      const data = (await response.json()) as { device?: ServerDevice | null };
      applyDeviceFromServer(data.device);
    } catch (error) {
      logEvent("register_device_failed", { error });
    }
  };

  const sendHeartbeat = async () => {
    try {
      const deviceUid = await getCurrentDeviceUid();
      if (!deviceUid) {
        logEvent("heartbeat_skipped_missing_uid");
        return;
      }

      const response = await fetch(`${SERVER}/devices/heartbeat`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ deviceUid }),
      });

      if (!response.ok) {
        return;
      }

      const data = (await response.json()) as { device?: ServerDevice | null };
      applyDeviceFromServer(data.device);
    } catch (error) {
      logEvent("heartbeat_failed", { error });
    }
  };

  const updateCommandStatus = async (
    id: string,
    status: string,
    failureReason?: string | null
  ) => {
    try {
      const payload: { status: string; failureReason?: string } = { status };
      if (typeof failureReason === "string" && failureReason.trim()) {
        payload.failureReason = failureReason.trim();
      }
      logEvent("command_status_update_request", {
        commandId: id,
        status,
        failureReason: payload.failureReason ?? null,
      });
      const response = await fetch(`${SERVER}/commands/${id}/status`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      if (!response.ok) {
        logEvent("command_status_update_rejected", {
          commandId: id,
          status,
          code: response.status,
        });
        return;
      }
      logEvent("command_status_update_success", { commandId: id, status });
    } catch (error) {
      logEvent("update_command_status_failed", { id, status, failureReason, error });
    }
  };

  const rememberProcessedCommandId = (commandId: string) => {
    if (!commandId || processedCommandIds.current.has(commandId)) {
      return;
    }
    processedCommandIds.current.add(commandId);
    while (processedCommandIds.current.size > MAX_TRACKED_PROCESSED_COMMAND_IDS) {
      const oldest = processedCommandIds.current.values().next().value as string | undefined;
      if (!oldest) {
        break;
      }
      processedCommandIds.current.delete(oldest);
    }
  };

  const hasLocalDuplicate = (commandId: string): boolean => {
    if (processedCommandIds.current.has(commandId)) {
      logEvent("command_duplicate_ignored_processed", { commandId });
      return true;
    }
    if (inFlightCommandIds.current.has(commandId)) {
      logEvent("command_duplicate_ignored_in_flight", { commandId });
      return true;
    }
    return false;
  };

  const claimNextCommand = async (): Promise<ServerCallCommand | null> => {
    const deviceUid = await getCurrentDeviceUid();
    if (!deviceUid) {
      logEvent("claim_command_skipped_missing_uid");
      return null;
    }

    try {
      logEvent("claim_command_request", { deviceUid });
      const response = await fetch(`${SERVER}/commands/claim`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ deviceUid }),
      });
      if (!response.ok) {
        logEvent("claim_command_rejected", {
          deviceUid,
          code: response.status,
        });
        return null;
      }

      const payload = (await response.json()) as ClaimNextCommandResponse;
      const command = payload.command ?? null;
      logEvent("claim_command_result", {
        deviceUid,
        commandId: command?.id ?? null,
        status: command?.status ?? null,
      });
      return command;
    } catch (error) {
      logEvent("claim_command_failed", { deviceUid, error });
      return null;
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

  const parseServerCommandDurationSeconds = (durationSeconds?: number | null): number | null => {
    if (typeof durationSeconds !== "number" || !Number.isFinite(durationSeconds) || durationSeconds <= 0) {
      return null;
    }

    const clamped = Math.min(durationSeconds, MAX_SIMPLE_CALL_DURATION_SECONDS);
    return Math.round(clamped);
  };

  const parseServerAutoHangupSeconds = (autoHangupSeconds?: number | null): number | null => {
    if (
      typeof autoHangupSeconds !== "number" ||
      !Number.isFinite(autoHangupSeconds) ||
      autoHangupSeconds <= 0
    ) {
      return null;
    }

    const clamped = Math.max(1, Math.min(autoHangupSeconds, MAX_AUTO_HANGUP_SECONDS));
    return Math.round(clamped);
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
        logEvent("command_execution_started", {
          commandId,
          action: "call",
          source,
          durationSeconds: commandDurationSeconds ?? null,
        });
      }

      const hasPermissions = await requestAndroidPermissions();
      if (!hasPermissions) {
        if (commandId) {
          await updateCommandStatus(commandId, "failed");
        }
        return false;
      }

      if (source === "server") {
        logEvent("command_selected_for_execution", {
          phoneNumber: normalized,
          commandId,
          durationSeconds: commandDurationSeconds ?? null,
        });
      }

      const callResult =
        source === "server"
          ? await startServerCommandCall(
              normalized,
              parseServerCommandDurationSeconds(commandDurationSeconds)
            )
          : await startSimpleCall(normalized, parseSimpleCallDurationMs());
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
        logEvent("command_execution_finished", {
          commandId,
          action: "call",
          result: "executed",
        });
      }

      setStatusMessage(source === "server" ? "Outgoing call started from server command" : "Outgoing call started");
      return true;
    } catch (error) {
      logEvent("execute_outgoing_call_failed", { error, source, commandId, rawPhoneNumber });
      if (commandId) {
        await updateCommandStatus(commandId, "failed");
        logEvent("command_execution_finished", {
          commandId,
          action: "call",
          result: "failed",
        });
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
      logEvent("command_execution_started", { commandId, action: "end" });
      const result = await endCurrentCall();
      await refreshStatus();

      if (result.ended) {
        await updateCommandStatus(commandId, "executed");
        logEvent("command_execution_finished", {
          commandId,
          action: "end",
          result: "executed",
        });
        setStatusMessage("Current call ended from server command");
        return true;
      }

      await updateCommandStatus(commandId, "failed");
      logEvent("command_execution_finished", {
        commandId,
        action: "end",
        result: "failed",
      });
      setStatusMessage("Could not end current call from server command");
      return false;
    } catch (error) {
      logEvent("execute_end_command_failed", { error, commandId });
      await updateCommandStatus(commandId, "failed");
      logEvent("command_execution_finished", {
        commandId,
        action: "end",
        result: "failed",
      });
      setStatusMessage("Failed to execute end command");
      return false;
    } finally {
      inFlightCommandIds.current.delete(commandId);
    }
  };

  const executeSmsCommand = async (
    rawPhoneNumber: string,
    rawMessage: string,
    commandId: string
  ): Promise<boolean> => {
    const normalizedPhone = normalizePhoneNumber(rawPhoneNumber);
    if (!normalizedPhone) {
      await updateCommandStatus(commandId, "failed", "Invalid SMS phone number");
      setStatusMessage("Invalid SMS phone number from server command");
      return false;
    }

    const normalizedMessage = rawMessage.trim();
    if (!normalizedMessage) {
      await updateCommandStatus(commandId, "failed", "SMS message is empty");
      setStatusMessage("SMS command message is empty");
      return false;
    }

    try {
      inFlightCommandIds.current.add(commandId);
      logEvent("command_execution_started", { commandId, action: "sms" });

      const hasPermissions = await requestAndroidPermissions([
        PermissionsAndroid.PERMISSIONS.SEND_SMS,
      ]);
      if (!hasPermissions) {
        await updateCommandStatus(commandId, "failed", SMS_PERMISSION_DENIED_REASON);
        logEvent("command_execution_finished", {
          commandId,
          action: "sms",
          result: "failed",
        });
        setStatusMessage(`SMS command failed: ${SMS_PERMISSION_DENIED_REASON}`);
        return false;
      }

      const smsResult = await startServerCommandSms(normalizedPhone, normalizedMessage);
      if (!smsResult.success) {
        const failureReason =
          smsResult.reason === "permission_denied" ? SMS_PERMISSION_DENIED_REASON : smsResult.message;
        await updateCommandStatus(commandId, "failed", failureReason);
        logEvent("command_execution_finished", {
          commandId,
          action: "sms",
          result: "failed",
        });
        setStatusMessage(`SMS command failed: ${failureReason}`);
        return false;
      }

      await updateCommandStatus(commandId, "executed");
      logEvent("command_execution_finished", {
        commandId,
        action: "sms",
        result: "executed",
      });
      setStatusMessage("SMS sent from server command");
      return true;
    } catch (error) {
      logEvent("execute_sms_command_failed", { error, commandId });
      await updateCommandStatus(commandId, "failed", "Failed to send SMS");
      logEvent("command_execution_finished", {
        commandId,
        action: "sms",
        result: "failed",
      });
      setStatusMessage("Failed to execute SMS command");
      return false;
    } finally {
      inFlightCommandIds.current.delete(commandId);
    }
  };

  const executeAutoAnswerCommand = async (command: ServerCallCommand): Promise<boolean> => {
    const commandId = command.id;
    try {
      inFlightCommandIds.current.add(commandId);
      logEvent("command_execution_started", { commandId, action: "auto_answer" });

      if (typeof command.enabled !== "boolean") {
        await updateCommandStatus(commandId, "failed", "AUTO_ANSWER command missing enabled");
        logEvent("command_execution_finished", {
          commandId,
          action: "auto_answer",
          result: "failed",
        });
        setStatusMessage("AUTO_ANSWER command missing enabled");
        return false;
      }

      if (command.enabled) {
        const hasPermissions = await requestAndroidPermissions();
        if (!hasPermissions) {
          await updateCommandStatus(commandId, "failed", "Missing required phone permissions");
          logEvent("command_execution_finished", {
            commandId,
            action: "auto_answer",
            result: "failed",
          });
          setStatusMessage("AUTO_ANSWER failed: missing required phone permissions");
          return false;
        }

        const hasRequestedAutoHangup =
          command.autoHangupSeconds !== undefined && command.autoHangupSeconds !== null;
        const parsedAutoHangupSeconds = parseServerAutoHangupSeconds(command.autoHangupSeconds);
        if (hasRequestedAutoHangup && parsedAutoHangupSeconds === null) {
          await updateCommandStatus(
            commandId,
            "failed",
            "AUTO_ANSWER command has invalid autoHangupSeconds"
          );
          logEvent("command_execution_finished", {
            commandId,
            action: "auto_answer",
            result: "failed",
          });
          setStatusMessage("AUTO_ANSWER command has invalid autoHangupSeconds");
          return false;
        }

        const currentStatus = await getStatus();
        const requestedSeconds = parsedAutoHangupSeconds ?? currentStatus.autoHangupSeconds;
        const status = await enableAutoAnswer(requestedSeconds);
        updateUiFromStatus(status);
        await updateCommandStatus(commandId, "executed");
        logEvent("command_execution_finished", {
          commandId,
          action: "auto_answer",
          result: "executed",
        });
        setStatusMessage(`Auto Answer ON (${status.autoHangupSeconds}s) from server command`);
        return true;
      }

      const status = await disableAutoAnswer();
      updateUiFromStatus(status);
      await updateCommandStatus(commandId, "executed");
      logEvent("command_execution_finished", {
        commandId,
        action: "auto_answer",
        result: "executed",
      });
      setStatusMessage("Auto Answer OFF from server command");
      return true;
    } catch (error) {
      logEvent("execute_auto_answer_command_failed", { error, commandId, command });
      await updateCommandStatus(commandId, "failed", "Failed to apply AUTO_ANSWER command");
      logEvent("command_execution_finished", {
        commandId,
        action: "auto_answer",
        result: "failed",
      });
      setStatusMessage("Failed to execute AUTO_ANSWER command");
      return false;
    } finally {
      inFlightCommandIds.current.delete(commandId);
    }
  };

  const executeOpenAppCommand = async (
    commandId: string,
    rawAppName: string | null | undefined,
    resolvedPackageName: string | null | undefined
  ): Promise<boolean> => {
    const normalizedAppName = normalizeAppName(rawAppName);
    if (!normalizedAppName) {
      await updateCommandStatus(commandId, "failed", "OPEN_APP command missing appName");
      setStatusMessage("OPEN_APP command failed: missing appName");
      logEvent("open_app_invalid", { commandId, rawAppName });
      return false;
    }

    try {
      inFlightCommandIds.current.add(commandId);
      logEvent("command_execution_started", {
        commandId,
        action: "open_app",
        appName: normalizedAppName,
        resolvedPackageName: resolvedPackageName ?? null,
      });
      logEvent("open_app_received", {
        commandId,
        appName: normalizedAppName,
        resolvedPackageName: resolvedPackageName ?? null,
      });

      const result = await openInstalledApp(normalizedAppName, resolvedPackageName ?? null);
      if (!result.success) {
        const failureReason =
          typeof result.message === "string" && result.message.trim()
            ? result.message.trim()
            : "App not installed";
        await updateCommandStatus(commandId, "failed", failureReason);
        logEvent("command_execution_finished", {
          commandId,
          action: "open_app",
          result: "failed",
          reason: result.reason,
          message: failureReason,
        });
        setStatusMessage(`OPEN_APP failed: ${failureReason}`);
        return false;
      }

      await updateCommandStatus(commandId, "executed");
      logEvent("open_app_launched", {
        commandId,
        appName: normalizedAppName,
        packageName: result.packageName ?? null,
        matchedLabel: result.matchedLabel ?? null,
      });
      logEvent("command_execution_finished", {
        commandId,
        action: "open_app",
        result: "executed",
      });
      setStatusMessage(
        result.matchedLabel
          ? `OPEN_APP executed: ${result.matchedLabel}`
          : `OPEN_APP executed: ${normalizedAppName}`
      );
      return true;
    } catch (error) {
      logEvent("execute_open_app_command_failed", {
        error,
        commandId,
        appName: rawAppName,
        resolvedPackageName: resolvedPackageName ?? null,
      });
      await updateCommandStatus(commandId, "failed", "App not installed");
      logEvent("command_execution_finished", {
        commandId,
        action: "open_app",
        result: "failed",
      });
      setStatusMessage("OPEN_APP failed: App not installed");
      return false;
    } finally {
      inFlightCommandIds.current.delete(commandId);
    }
  };

  const executeOpenUrlCommand = async (
    commandId: string,
    rawUrl: string | null | undefined
  ): Promise<boolean> => {
    const normalizedUrl = normalizeHttpUrl(rawUrl);
    if (!normalizedUrl) {
      await updateCommandStatus(commandId, "failed", "OPEN_URL command has invalid url");
      setStatusMessage("OPEN_URL command failed: invalid URL");
      logEvent("open_url_invalid", { commandId, rawUrl });
      return false;
    }

    try {
      inFlightCommandIds.current.add(commandId);
      logEvent("command_execution_started", {
        commandId,
        action: "open_url",
        url: normalizedUrl,
      });
      logEvent("open_url_received", { commandId, url: normalizedUrl });

      const result = await openInAppWebView(normalizedUrl);
      setWebViewState({
        isOpen: result.isOpen,
        currentUrl: result.currentUrl,
      });

      if (!result.success) {
        await updateCommandStatus(
          commandId,
          "failed",
          result.message || "Failed to open in-app WebView"
        );
        logEvent("command_execution_finished", {
          commandId,
          action: "open_url",
          result: "failed",
          reason: result.reason,
        });
        setStatusMessage(`OPEN_URL failed: ${result.message}`);
        return false;
      }

      if (result.replacedExisting) {
        logEvent("webview_url_replaced", {
          commandId,
          url: result.url ?? normalizedUrl,
        });
      } else {
        logEvent("webview_opened", {
          commandId,
          url: result.url ?? normalizedUrl,
        });
      }

      await updateCommandStatus(commandId, "executed");
      logEvent("command_execution_finished", {
        commandId,
        action: "open_url",
        result: "executed",
        replacedExisting: result.replacedExisting,
      });
      setStatusMessage(
        result.replacedExisting
          ? "WebView URL replaced from server command"
          : "WebView opened from server command"
      );
      return true;
    } catch (error) {
      logEvent("execute_open_url_command_failed", { error, commandId, rawUrl });
      await updateCommandStatus(commandId, "failed", "Failed to execute OPEN_URL command");
      logEvent("command_execution_finished", {
        commandId,
        action: "open_url",
        result: "failed",
      });
      setStatusMessage("Failed to execute OPEN_URL command");
      return false;
    } finally {
      inFlightCommandIds.current.delete(commandId);
    }
  };

  const executeCloseWebViewCommand = async (commandId: string): Promise<boolean> => {
    try {
      inFlightCommandIds.current.add(commandId);
      logEvent("command_execution_started", { commandId, action: "close_webview" });
      logEvent("close_webview_received", { commandId });

      const result = await closeInAppWebView();
      setWebViewState({
        isOpen: result.isOpen,
        currentUrl: result.currentUrl,
      });

      if (!result.success) {
        await updateCommandStatus(
          commandId,
          "failed",
          result.message || "Failed to close in-app WebView"
        );
        logEvent("command_execution_finished", {
          commandId,
          action: "close_webview",
          result: "failed",
          reason: result.reason,
        });
        setStatusMessage(`CLOSE_WEBVIEW failed: ${result.message}`);
        return false;
      }

      await updateCommandStatus(commandId, "executed");
      if (result.noOp) {
        logEvent("close_webview_ignored_no_active_webview", { commandId });
        setStatusMessage("CLOSE_WEBVIEW applied (no active WebView)");
      } else {
        logEvent("webview_closed", { commandId });
        setStatusMessage("WebView closed from server command");
      }

      logEvent("command_execution_finished", {
        commandId,
        action: "close_webview",
        result: "executed",
        noOp: result.noOp,
      });
      return true;
    } catch (error) {
      logEvent("execute_close_webview_command_failed", { error, commandId });
      await updateCommandStatus(commandId, "failed", "Failed to execute CLOSE_WEBVIEW command");
      logEvent("command_execution_finished", {
        commandId,
        action: "close_webview",
        result: "failed",
      });
      setStatusMessage("Failed to execute CLOSE_WEBVIEW command");
      return false;
    } finally {
      inFlightCommandIds.current.delete(commandId);
    }
  };

  const executeReturnToAutoCallCommand = async (commandId: string): Promise<boolean> => {
    try {
      inFlightCommandIds.current.add(commandId);
      logEvent("command_execution_started", { commandId, action: "return_to_autocall" });
      logEvent("return_to_autocall_received", { commandId });

      const result = await returnToAutoCall();
      if (!result.success) {
        const failureReason =
          typeof result.message === "string" && result.message.trim()
            ? result.message.trim()
            : "Failed to return to AutoCall";
        await updateCommandStatus(commandId, "failed", failureReason);
        logEvent("command_execution_finished", {
          commandId,
          action: "return_to_autocall",
          result: "failed",
          reason: result.reason,
          message: failureReason,
        });
        setStatusMessage(`RETURN_TO_AUTOCALL failed: ${failureReason}`);
        return false;
      }

      await refreshInAppWebViewState();
      await updateCommandStatus(commandId, "executed");
      if (result.noOp) {
        logEvent("return_to_autocall_noop_already_foreground", { commandId });
        setStatusMessage("RETURN_TO_AUTOCALL applied (already on AutoCall)");
      } else {
        logEvent("return_to_autocall_opened_autocall", {
          commandId,
          webViewWasOpen: result.webViewWasOpen,
        });
        setStatusMessage("Returned to AutoCall from server command");
      }
      logEvent("command_execution_finished", {
        commandId,
        action: "return_to_autocall",
        result: "executed",
        noOp: result.noOp,
        webViewWasOpen: result.webViewWasOpen,
      });
      return true;
    } catch (error) {
      logEvent("execute_return_to_autocall_command_failed", { error, commandId });
      await updateCommandStatus(commandId, "failed", "Failed to execute RETURN_TO_AUTOCALL command");
      logEvent("command_execution_finished", {
        commandId,
        action: "return_to_autocall",
        result: "failed",
      });
      setStatusMessage("Failed to execute RETURN_TO_AUTOCALL command");
      return false;
    } finally {
      inFlightCommandIds.current.delete(commandId);
    }
  };

  const resolveCommandAction = (
    command: ServerCallCommand
  ): ServerCommandAction => {
    const explicitAction = command.action;
    if (
      explicitAction === "call" ||
      explicitAction === "end" ||
      explicitAction === "sms" ||
      explicitAction === "auto_answer" ||
      explicitAction === "open_url" ||
      explicitAction === "close_webview" ||
      explicitAction === "open_app" ||
      explicitAction === "return_to_autocall"
    ) {
      return explicitAction;
    }

    if (command.type === "END") return "end";
    if (command.type === "SMS") return "sms";
    if (command.type === "AUTO_ANSWER") return "auto_answer";
    if (command.type === "OPEN_URL") return "open_url";
    if (command.type === "CLOSE_WEBVIEW") return "close_webview";
    if (command.type === "OPEN_APP") return "open_app";
    if (command.type === "RETURN_TO_AUTOCALL") return "return_to_autocall";
    return "call";
  };

  const pollCommands = async () => {
    if (pollInProgressRef.current) {
      logEvent("poll_commands_skipped_already_running");
      return;
    }

    pollInProgressRef.current = true;
    try {
      const command = await claimNextCommand();
      setNextServerCommand(command);
      if (!command) {
        return;
      }

      const commandId = typeof command.id === "string" ? command.id.trim() : "";
      if (!commandId) {
        logEvent("claim_command_invalid_id", { command });
        return;
      }

      if (hasLocalDuplicate(commandId)) {
        return;
      }
      rememberProcessedCommandId(commandId);

      const action = resolveCommandAction(command);
      logEvent("command_selected_for_execution", {
        commandId,
        action,
        type: command.type,
      });

      if (action === "end") {
        await executeEndCommand(commandId);
        return;
      }

      if (action === "auto_answer") {
        await executeAutoAnswerCommand(command);
        return;
      }

      if (action === "open_app") {
        if (!command.appName) {
          await updateCommandStatus(commandId, "failed", "OPEN_APP command missing appName");
          return;
        }
        await executeOpenAppCommand(
          commandId,
          command.appName,
          command.resolvedPackageName ?? null
        );
        return;
      }

      if (action === "open_url") {
        if (!command.url) {
          await updateCommandStatus(commandId, "failed", "OPEN_URL command missing url");
          return;
        }
        await executeOpenUrlCommand(commandId, command.url);
        return;
      }

      if (action === "close_webview") {
        await executeCloseWebViewCommand(commandId);
        return;
      }

      if (action === "return_to_autocall") {
        await executeReturnToAutoCallCommand(commandId);
        return;
      }

      if (action === "sms") {
        if (!command.phoneNumber) {
          await updateCommandStatus(commandId, "failed", "SMS command missing phoneNumber");
          return;
        }
        if (!command.message) {
          await updateCommandStatus(commandId, "failed", "SMS command missing message");
          return;
        }
        await executeSmsCommand(command.phoneNumber, command.message, commandId);
        return;
      }

      if (!command.phoneNumber) {
        await updateCommandStatus(commandId, "failed", "CALL command missing phoneNumber");
        return;
      }

      await executeOutgoingCall(
        command.phoneNumber,
        "server",
        commandId,
        command.durationSeconds ?? null
      );
    } catch (error) {
      logEvent("poll_commands_failed", { error });
    } finally {
      pollInProgressRef.current = false;
    }
  };

  const formatNextServerCommand = (command: ServerCallCommand | null): string => {
    if (!command) {
      return "No pending commands";
    }

    if (command.type === "AUTO_ANSWER") {
      if (command.enabled === true) {
        const parsedSeconds = parseServerAutoHangupSeconds(command.autoHangupSeconds);
        return parsedSeconds ? `AUTO_ANSWER ON (${parsedSeconds}s)` : "AUTO_ANSWER ON";
      }
      if (command.enabled === false) {
        return "AUTO_ANSWER OFF";
      }
      return "AUTO_ANSWER";
    }

    if (command.type === "OPEN_URL") {
      const normalizedUrl = normalizeHttpUrl(command.url);
      return normalizedUrl ? `OPEN_URL (${normalizedUrl})` : "OPEN_URL";
    }

    if (command.type === "CLOSE_WEBVIEW") {
      return "CLOSE_WEBVIEW";
    }

    if (command.type === "OPEN_APP") {
      const normalizedAppName = normalizeAppName(command.appName);
      return normalizedAppName ? `OPEN_APP (${normalizedAppName})` : "OPEN_APP";
    }

    if (command.type === "RETURN_TO_AUTOCALL") {
      return "RETURN_TO_AUTOCALL";
    }

    return `${command.type}${command.phoneNumber ? ` (${command.phoneNumber})` : ""}`;
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
      await refreshDeviceIdentity();
      await refreshStatus();
      await refreshInAppWebViewState();
      await registerDevice();
      await sendHeartbeat();
      await pollCommands();

      heartbeatTimer = setInterval(() => {
        void sendHeartbeat();
      }, POLL_INTERVAL_MS);

      pollTimer = setInterval(() => {
        void pollCommands();
      }, POLL_INTERVAL_MS);

      statusTimer = setInterval(() => {
        void refreshStatus();
        void refreshInAppWebViewState();
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


        <View style={styles.statusBox}>
          <Text style={styles.statusLine}>Device UID: {deviceIdentity.deviceUid || "--"}</Text>
          <Text style={styles.statusLine}>Device Name: {deviceIdentity.deviceName || "--"}</Text>
          <Text style={styles.statusLine}>Auto Answer: {uiState.autoAnswerEnabled ? "ON" : "OFF"}</Text>
          <Text style={styles.statusLine}>Auto Hangup: {uiState.autoHangupSeconds}s</Text>
          <Text style={styles.statusLine}>Hangup Timer: {uiState.hangupScheduled ? "Scheduled" : "Idle"}</Text>
          <Text style={styles.statusLine}>Last Event: {uiState.lastEvent || "--"}</Text>
          <Text style={styles.statusLine}>Event Time: {formatTime(uiState.lastEventAt)}</Text>
          <Text style={styles.statusLine}>
            Next Server Command:{" "}
            {formatNextServerCommand(nextServerCommand)}
          </Text>
          <Text style={styles.statusLine}>WebView Open: {webViewState.isOpen ? "YES" : "NO"}</Text>
          <Text style={styles.statusLine}>
            WebView URL: {webViewState.currentUrl ? webViewState.currentUrl : "--"}
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
