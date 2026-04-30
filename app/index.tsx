import { useEffect, useRef, useState } from "react";
import {
  Animated,
  AppState,
  Image,
  ImageBackground,
  NativeModules,
  PermissionsAndroid,
  Platform,
  Pressable,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from "react-native";
import {
  closeInAppWebView,
  disableAutoAnswer,
  downloadDataForCommand,
  enableAutoAnswer,
  endCurrentCall,
  getDeviceIdentity,
  getInAppWebViewState,
  getStatus,
  openInAppWebView,
  openInstalledApp,
  returnToAutoCall,
  startServerCommandCall,
  startServerCommandSms,
  startSimpleCall,
  syncDeviceIdentity,
  takePendingScreenMirrorStartCommandId,
  type AutoAnswerStatus,
  type DeviceIdentity,
  type InAppWebViewState,
} from "../src/native/autoCallNative";
import {
  getScreenMirrorState,
  requestScreenMirrorPermission,
  startScreenMirrorFromCommand,
  stopScreenMirroring,
  type ScreenMirrorState,
} from "../src/native/screenMirrorNative";

const SERVER = "https://serverautocall-production.up.railway.app";
const POLL_INTERVAL_MS = 10000;
const DEFAULT_PHONE = "05";
const DEFAULT_HANGUP_SECONDS = 20;
const DEFAULT_SIMPLE_CALL_DURATION_SECONDS = 20;
const MAX_AUTO_HANGUP_SECONDS = 600;
const MAX_SIMPLE_CALL_DURATION_SECONDS = 3600;
const MIN_DOWNLOAD_SIZE_MB = 10;
const MAX_DOWNLOAD_SIZE_MB = 1000;
const MAX_TRACKED_PROCESSED_COMMAND_IDS = 500;
const LOG_PREFIX = "[AutoCall/UI]";
const SMS_PERMISSION_DENIED_REASON = "SEND_SMS permission denied";
const DEVICE_UID_REGEX = /^[a-z0-9]{5}$/;
const DEVICE_TOKEN_REGEX = /^[a-f0-9]{64}$/;
const DOWNLOAD_DATA_SUCCESS_MESSAGE = "Download data command executed successfully";
const DOWNLOAD_DATA_BANNER_DURATION_MS = 10_000;
const INTRO_SCREEN_DURATION_MS = 3000;
const INTRO_SCREEN_FADE_MS = 320;

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
    | "return_to_autocall"
    | "download_data"
    | "start_screen_mirror"
    | "stop_screen_mirror";
  type:
    | "CALL"
    | "END"
    | "SMS"
    | "AUTO_ANSWER"
    | "OPEN_URL"
    | "CLOSE_WEBVIEW"
    | "OPEN_APP"
    | "RETURN_TO_AUTOCALL"
    | "DOWNLOAD_DATA"
    | "START_SCREEN_MIRROR"
    | "STOP_SCREEN_MIRROR";
  phoneNumber?: string | null;
  message?: string | null;
  url?: string | null;
  appName?: string | null;
  resolvedPackageName?: string | null;
  durationSeconds?: number | null;
  downloadSizeMb?: number | null;
  downloadDurationSeconds?: number | null;
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

type ServerDeviceResponse = {
  success?: boolean;
  device?: ServerDevice | null;
  deviceToken?: string | null;
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
  | "return_to_autocall"
  | "download_data"
  | "start_screen_mirror"
  | "stop_screen_mirror";

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

const emptyScreenMirrorState: ScreenMirrorState = {
  status: "idle",
  reason: null,
  permissionGranted: false,
  isSharing: false,
  updatedAt: 0,
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

const normalizeDeviceToken = (value: string | null | undefined): string => {
  if (typeof value !== "string") {
    return "";
  }
  const normalized = value.trim().toLowerCase();
  return DEVICE_TOKEN_REGEX.test(normalized) ? normalized : "";
};

const normalizeCallPhoneNumber = (input: string): string | null => {
  const trimmed = input.trim();
  if (!trimmed) return null;
  return trimmed;
};

const normalizeSmsPhoneNumber = (input: string): string | null => {
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
  deviceToken: "",
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
  const [screenMirrorState, setScreenMirrorState] =
    useState<ScreenMirrorState>(emptyScreenMirrorState);
  const [downloadSuccessBannerVisible, setDownloadSuccessBannerVisible] = useState(false);
  const [showIntroOverlay, setShowIntroOverlay] = useState(true);
  const downloadSuccessBannerTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const introOverlayOpacity = useRef(new Animated.Value(1)).current;
  const introOverlayTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const lastHandledDownloadSuccessEventAtRef = useRef(0);
  const inFlightCommandIds = useRef<Set<string>>(new Set());
  const processedCommandIds = useRef<Set<string>>(new Set());
  const pollInProgressRef = useRef(false);
  const screenMirrorPendingCommandProcessingRef = useRef(false);
  const deviceUidRef = useRef<string>("");
  const deviceNameRef = useRef<string>("");
  const deviceTokenRef = useRef<string>("");

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
    const normalizedToken = normalizeDeviceToken(identity.deviceToken);

    deviceUidRef.current = normalizedUid;
    deviceNameRef.current = normalizedName;
    deviceTokenRef.current = normalizedToken;
    setDeviceIdentity({
      deviceUid: normalizedUid,
      deviceName: normalizedName,
      deviceToken: normalizedToken,
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

  const getCurrentDeviceCredentials = async (): Promise<{
    deviceUid: string;
    deviceToken: string | null;
  } | null> => {
    const deviceUid = await getCurrentDeviceUid();
    if (!deviceUid) return null;

    const cachedToken = normalizeDeviceToken(deviceTokenRef.current);
    if (cachedToken) {
      deviceTokenRef.current = cachedToken;
      return { deviceUid, deviceToken: cachedToken };
    }

    const identity = await refreshDeviceIdentity();
    const normalizedToken = normalizeDeviceToken(identity?.deviceToken);
    deviceTokenRef.current = normalizedToken;
    return {
      deviceUid,
      deviceToken: normalizedToken || null,
    };
  };

  const applyDeviceFromServer = async (response?: ServerDeviceResponse | null) => {
    if (!response || typeof response !== "object") {
      return;
    }
    const device = response.device;
    if (!device || typeof device !== "object") return;

    const serverUid = normalizeDeviceUid(device.deviceUid);
    if (!serverUid) {
      return;
    }

    const currentUid = normalizeDeviceUid(deviceUidRef.current);
    if (!currentUid || serverUid !== currentUid) {
      return;
    }

    const serverToken = normalizeDeviceToken(response.deviceToken);
    const serverName =
      typeof device.deviceName === "string" && device.deviceName.trim()
        ? device.deviceName.trim()
        : "";

    if (serverUid) {
      try {
        const syncedIdentity = await syncDeviceIdentity(
          serverUid,
          serverName || null,
          serverToken || null
        );
        applyDeviceIdentity(syncedIdentity);
        return;
      } catch (error) {
        logEvent("device_identity_sync_failed", { error });
      }
    }

    deviceUidRef.current = currentUid;
    if (serverName) {
      deviceNameRef.current = serverName;
    }
    if (serverToken) {
      deviceTokenRef.current = serverToken;
    }
    setDeviceIdentity(() => ({
      deviceUid: currentUid,
      deviceName: serverName || deviceNameRef.current,
      deviceToken: serverToken || deviceTokenRef.current,
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

  const refreshScreenMirrorState = async () => {
    try {
      const snapshot = await getScreenMirrorState();
      setScreenMirrorState(snapshot);
      logEvent("screen_mirror_state_refresh", snapshot);
    } catch (error) {
      logEvent("screen_mirror_state_refresh_failed", { error });
    }
  };

  const showDownloadSuccessBanner = () => {
    if (downloadSuccessBannerTimeoutRef.current) {
      clearTimeout(downloadSuccessBannerTimeoutRef.current);
      downloadSuccessBannerTimeoutRef.current = null;
    }
    setDownloadSuccessBannerVisible(true);
    downloadSuccessBannerTimeoutRef.current = setTimeout(() => {
      setDownloadSuccessBannerVisible(false);
      downloadSuccessBannerTimeoutRef.current = null;
    }, DOWNLOAD_DATA_BANNER_DURATION_MS);
  };

  useEffect(() => {
    console.log(`${LOG_PREFIX} NativeModules`, NativeModules);
  }, []);

  useEffect(() => {
    const normalizedLastEventAt = Number(uiState.lastEventAt || 0);
    if (
      uiState.lastEvent !== DOWNLOAD_DATA_SUCCESS_MESSAGE ||
      normalizedLastEventAt <= 0 ||
      normalizedLastEventAt <= lastHandledDownloadSuccessEventAtRef.current
    ) {
      return;
    }

    lastHandledDownloadSuccessEventAtRef.current = normalizedLastEventAt;
    showDownloadSuccessBanner();
  }, [uiState.lastEvent, uiState.lastEventAt]);

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
      const credentials = await getCurrentDeviceCredentials();
      if (!credentials) {
        logEvent("register_device_skipped_missing_uid");
        return;
      }
      const { deviceUid, deviceToken } = credentials;

      const response = await fetch(`${SERVER}/devices/register`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Device-Uid": deviceUid,
          ...(deviceToken ? { "X-Device-Token": deviceToken } : {}),
        },
        body: JSON.stringify({
          deviceUid,
          deviceToken: deviceToken || null,
        }),
      });

      if (!response.ok) {
        logEvent("register_device_rejected", { code: response.status, deviceUid });
        return;
      }

      const data = (await response.json()) as ServerDeviceResponse;
      await applyDeviceFromServer(data);
    } catch (error) {
      logEvent("register_device_failed", { error });
    }
  };

  const sendHeartbeat = async () => {
    try {
      const credentials = await getCurrentDeviceCredentials();
      if (!credentials) {
        logEvent("heartbeat_skipped_missing_uid");
        return;
      }
      const { deviceUid, deviceToken } = credentials;

      const response = await fetch(`${SERVER}/devices/heartbeat`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Device-Uid": deviceUid,
          ...(deviceToken ? { "X-Device-Token": deviceToken } : {}),
        },
        body: JSON.stringify({
          deviceUid,
          deviceToken: deviceToken || null,
        }),
      });

      if (!response.ok) {
        logEvent("heartbeat_rejected", { code: response.status, deviceUid });
        return;
      }

      const data = (await response.json()) as ServerDeviceResponse;
      await applyDeviceFromServer(data);
    } catch (error) {
      logEvent("heartbeat_failed", { error });
    }
  };

  const updateCommandStatus = async (
    id: string,
    status: string,
    failureReason?: string | null,
    downloadDurationSeconds?: number | null
  ) => {
    try {
      const credentials = await getCurrentDeviceCredentials();
      if (!credentials) {
        logEvent("command_status_update_skipped_missing_device_credentials", {
          commandId: id,
          status,
        });
        return;
      }

      const payload: {
        deviceUid: string;
        deviceToken?: string | null;
        status: string;
        failureReason?: string;
        downloadDurationSeconds?: number;
      } = {
        deviceUid: credentials.deviceUid,
        status,
      };
      if (credentials.deviceToken) {
        payload.deviceToken = credentials.deviceToken;
      }
      if (typeof failureReason === "string" && failureReason.trim()) {
        payload.failureReason = failureReason.trim();
      }
      if (
        typeof downloadDurationSeconds === "number" &&
        Number.isFinite(downloadDurationSeconds) &&
        downloadDurationSeconds > 0
      ) {
        payload.downloadDurationSeconds = Math.round(downloadDurationSeconds);
      }
      logEvent("command_status_update_request", {
        commandId: id,
        status,
        failureReason: payload.failureReason ?? null,
        downloadDurationSeconds: payload.downloadDurationSeconds ?? null,
      });
      const response = await fetch(`${SERVER}/commands/${id}/status`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Device-Uid": credentials.deviceUid,
          ...(credentials.deviceToken ? { "X-Device-Token": credentials.deviceToken } : {}),
        },
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
      logEvent("update_command_status_failed", {
        id,
        status,
        failureReason,
        downloadDurationSeconds: downloadDurationSeconds ?? null,
        error,
      });
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
    const credentials = await getCurrentDeviceCredentials();
    if (!credentials) {
      logEvent("claim_command_skipped_missing_uid");
      return null;
    }
    const { deviceUid, deviceToken } = credentials;

    try {
      logEvent("claim_command_request", { deviceUid });
      const response = await fetch(`${SERVER}/commands/claim`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Device-Uid": deviceUid,
          ...(deviceToken ? { "X-Device-Token": deviceToken } : {}),
        },
        body: JSON.stringify({
          deviceUid,
          deviceToken: deviceToken || null,
        }),
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

  const parseServerDownloadSizeMb = (downloadSizeMb?: number | null): number | null => {
    if (
      typeof downloadSizeMb !== "number" ||
      !Number.isFinite(downloadSizeMb) ||
      downloadSizeMb % 1 !== 0
    ) {
      return null;
    }

    if (downloadSizeMb < MIN_DOWNLOAD_SIZE_MB || downloadSizeMb > MAX_DOWNLOAD_SIZE_MB) {
      return null;
    }

    return Math.round(downloadSizeMb);
  };

  const executeOutgoingCall = async (
    rawPhoneNumber: string,
    source: "server" | "manual",
    commandId?: string,
    commandDurationSeconds?: number | null
  ): Promise<boolean> => {
    const normalized = normalizeCallPhoneNumber(rawPhoneNumber);
    if (!normalized) {
      setStatusMessage("Invalid phone number");
      if (commandId) {
        await updateCommandStatus(commandId, "failed", "CALL command has invalid phoneNumber");
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

      const hasPermissions = await requestAndroidPermissions([
        PermissionsAndroid.PERMISSIONS.CALL_PHONE,
      ]);
      if (!hasPermissions) {
        if (commandId) {
          await updateCommandStatus(commandId, "failed", "CALL_PHONE permission denied");
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
          await updateCommandStatus(
            commandId,
            "failed",
            callResult.message || "Failed to start CALL command"
          );
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
        await updateCommandStatus(commandId, "failed", "Failed to start CALL command");
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
    const normalizedPhone = normalizeSmsPhoneNumber(rawPhoneNumber);
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

  const executeDownloadDataCommand = async (
    commandId: string,
    downloadSizeMb?: number | null,
    scheduledAt?: string | null
  ): Promise<boolean> => {
    const normalizedDownloadSizeMb = parseServerDownloadSizeMb(downloadSizeMb);
    if (normalizedDownloadSizeMb === null) {
      const failureReason =
        `DOWNLOAD_DATA command has invalid downloadSizeMb ` +
        `(expected ${MIN_DOWNLOAD_SIZE_MB}-${MAX_DOWNLOAD_SIZE_MB})`;
      await updateCommandStatus(commandId, "failed", failureReason);
      setStatusMessage(failureReason);
      logEvent("download_data_invalid_size", { commandId, downloadSizeMb: downloadSizeMb ?? null });
      return false;
    }

    try {
      inFlightCommandIds.current.add(commandId);
      logEvent("command_execution_started", {
        commandId,
        action: "download_data",
        downloadSizeMb: normalizedDownloadSizeMb,
        scheduledAt: scheduledAt ?? null,
      });
      // Scheduling is enforced by /commands/claim in the backend; claimed commands are due for execution.

      const result = await downloadDataForCommand(normalizedDownloadSizeMb);
      if (!result.success) {
        const failureReason =
          typeof result.message === "string" && result.message.trim()
            ? result.message.trim()
            : "Failed to execute DOWNLOAD_DATA command";
        await updateCommandStatus(commandId, "failed", failureReason);
        logEvent("command_execution_finished", {
          commandId,
          action: "download_data",
          result: "failed",
          reason: result.reason,
          message: failureReason,
        });
        setStatusMessage(`DOWNLOAD_DATA failed: ${failureReason}`);
        return false;
      }

      const durationSecondsRaw = result.downloadDurationSeconds;
      const normalizedDurationSeconds =
        typeof durationSecondsRaw === "number" &&
        Number.isFinite(durationSecondsRaw) &&
        durationSecondsRaw > 0
          ? Math.round(durationSecondsRaw)
          : null;

      if (!normalizedDurationSeconds) {
        const failureReason = "DOWNLOAD_DATA completed without valid duration";
        await updateCommandStatus(commandId, "failed", failureReason);
        logEvent("command_execution_finished", {
          commandId,
          action: "download_data",
          result: "failed",
          reason: "invalid_duration",
          message: failureReason,
        });
        setStatusMessage(`DOWNLOAD_DATA failed: ${failureReason}`);
        return false;
      }

      await updateCommandStatus(commandId, "executed", null, normalizedDurationSeconds);
      await refreshStatus();
      showDownloadSuccessBanner();
      logEvent("command_execution_finished", {
        commandId,
        action: "download_data",
        result: "executed",
        downloadSizeMb: normalizedDownloadSizeMb,
        downloadDurationSeconds: normalizedDurationSeconds,
      });
      setStatusMessage(
        `DOWNLOAD_DATA completed (${normalizedDownloadSizeMb} MB in ${normalizedDurationSeconds} sec)`
      );
      return true;
    } catch (error) {
      logEvent("execute_download_data_command_failed", { error, commandId, downloadSizeMb });
      await updateCommandStatus(commandId, "failed", "Failed to execute DOWNLOAD_DATA command");
      logEvent("command_execution_finished", {
        commandId,
        action: "download_data",
        result: "failed",
      });
      setStatusMessage("Failed to execute DOWNLOAD_DATA command");
      return false;
    } finally {
      inFlightCommandIds.current.delete(commandId);
    }
  };

  const executeStartScreenMirrorCommand = async (commandId: string): Promise<boolean> => {
    try {
      inFlightCommandIds.current.add(commandId);
      logEvent("command_execution_started", { commandId, action: "start_screen_mirror" });

      const stateBeforeStart = await getScreenMirrorState();
      if (!stateBeforeStart.permissionGranted) {
        const permissionResult = await requestScreenMirrorPermission();
        await refreshScreenMirrorState();

        if (!permissionResult.success) {
          const permissionFailureReasonRaw =
            typeof permissionResult.reason === "string" && permissionResult.reason.trim()
              ? permissionResult.reason.trim()
              : "screen_mirror_permission_not_granted";
          const permissionFailureReason =
            permissionFailureReasonRaw === "screen_mirror_activity_unavailable"
              ? "screen_mirror_permission_request_failed"
              : permissionFailureReasonRaw;
          await updateCommandStatus(commandId, "failed", permissionFailureReason);
          logEvent("command_execution_finished", {
            commandId,
            action: "start_screen_mirror",
            result: "failed",
            reason: permissionFailureReason,
            message: permissionResult.message,
          });
          setStatusMessage(`START_SCREEN_MIRROR failed: ${permissionResult.message}`);
          return false;
        }

        const stateAfterPermission = await getScreenMirrorState();
        if (!stateAfterPermission.permissionGranted) {
          await updateCommandStatus(commandId, "failed", "screen_mirror_permission_not_granted");
          logEvent("command_execution_finished", {
            commandId,
            action: "start_screen_mirror",
            result: "failed",
            reason: "screen_mirror_permission_not_granted",
          });
          setStatusMessage("START_SCREEN_MIRROR failed: permission not granted");
          return false;
        }
      } else {
        await refreshScreenMirrorState();
      }

      const stateReady = await getScreenMirrorState();
      if (!stateReady.permissionGranted) {
        await updateCommandStatus(commandId, "failed", "screen_mirror_permission_not_granted");
        logEvent("command_execution_finished", {
          commandId,
          action: "start_screen_mirror",
          result: "failed",
          reason: "screen_mirror_permission_not_granted",
        });
        setStatusMessage("START_SCREEN_MIRROR failed: permission not granted");
        return false;
      }

      const result = await startScreenMirrorFromCommand();
      if (!result.success) {
        const failureReason =
          typeof result.reason === "string" && result.reason.trim()
            ? result.reason.trim()
            : "start_screen_mirror_failed";
        await updateCommandStatus(commandId, "failed", failureReason);
        logEvent("command_execution_finished", {
          commandId,
          action: "start_screen_mirror",
          result: "failed",
          reason: failureReason,
          message: result.message,
        });
        setStatusMessage(`START_SCREEN_MIRROR failed: ${result.message}`);
        await refreshScreenMirrorState();
        return false;
      }

      await updateCommandStatus(commandId, "executed");
      await refreshScreenMirrorState();
      logEvent("command_execution_finished", {
        commandId,
        action: "start_screen_mirror",
        result: "executed",
      });
      setStatusMessage("START_SCREEN_MIRROR executed");
      return true;
    } catch (error) {
      logEvent("execute_start_screen_mirror_command_failed", { error, commandId });
      await updateCommandStatus(commandId, "failed", "start_screen_mirror_command_crash");
      logEvent("command_execution_finished", {
        commandId,
        action: "start_screen_mirror",
        result: "failed",
      });
      setStatusMessage("Failed to execute START_SCREEN_MIRROR command");
      return false;
    } finally {
      inFlightCommandIds.current.delete(commandId);
    }
  };

  const processPendingScreenMirrorStartCommand = async () => {
    if (screenMirrorPendingCommandProcessingRef.current) {
      return;
    }
    screenMirrorPendingCommandProcessingRef.current = true;
    try {
      const pendingCommandIdRaw = await takePendingScreenMirrorStartCommandId();
      const commandId =
        typeof pendingCommandIdRaw === "string" ? pendingCommandIdRaw.trim() : "";
      if (!commandId) {
        return;
      }
      rememberProcessedCommandId(commandId);
      logEvent("pending_screen_mirror_command_received", { commandId });
      await executeStartScreenMirrorCommand(commandId);
    } catch (error) {
      logEvent("pending_screen_mirror_command_processing_failed", { error });
    } finally {
      screenMirrorPendingCommandProcessingRef.current = false;
    }
  };

  const executeStopScreenMirrorCommand = async (commandId: string): Promise<boolean> => {
    try {
      inFlightCommandIds.current.add(commandId);
      logEvent("command_execution_started", { commandId, action: "stop_screen_mirror" });

      const result = await stopScreenMirroring("stopped_by_server_command");
      if (!result.success) {
        const failureReason =
          typeof result.reason === "string" && result.reason.trim()
            ? result.reason.trim()
            : "stop_screen_mirror_failed";
        await updateCommandStatus(commandId, "failed", failureReason);
        logEvent("command_execution_finished", {
          commandId,
          action: "stop_screen_mirror",
          result: "failed",
          reason: failureReason,
          message: result.message,
        });
        setStatusMessage(`STOP_SCREEN_MIRROR failed: ${result.message}`);
        await refreshScreenMirrorState();
        return false;
      }

      await updateCommandStatus(commandId, "executed");
      await refreshScreenMirrorState();
      logEvent("command_execution_finished", {
        commandId,
        action: "stop_screen_mirror",
        result: "executed",
      });
      setStatusMessage("STOP_SCREEN_MIRROR executed");
      return true;
    } catch (error) {
      logEvent("execute_stop_screen_mirror_command_failed", { error, commandId });
      await updateCommandStatus(commandId, "failed", "stop_screen_mirror_command_crash");
      logEvent("command_execution_finished", {
        commandId,
        action: "stop_screen_mirror",
        result: "failed",
      });
      setStatusMessage("Failed to execute STOP_SCREEN_MIRROR command");
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
      explicitAction === "return_to_autocall" ||
      explicitAction === "download_data" ||
      explicitAction === "start_screen_mirror" ||
      explicitAction === "stop_screen_mirror"
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
    if (command.type === "DOWNLOAD_DATA") return "download_data";
    if (command.type === "START_SCREEN_MIRROR") return "start_screen_mirror";
    if (command.type === "STOP_SCREEN_MIRROR") return "stop_screen_mirror";
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

      if (action === "download_data") {
        await executeDownloadDataCommand(
          commandId,
          command.downloadSizeMb ?? null,
          command.scheduledAt ?? null
        );
        return;
      }

      if (action === "start_screen_mirror") {
        await executeStartScreenMirrorCommand(commandId);
        return;
      }

      if (action === "stop_screen_mirror") {
        await executeStopScreenMirrorCommand(commandId);
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

    if (command.type === "DOWNLOAD_DATA") {
      const normalizedDownloadSizeMb = parseServerDownloadSizeMb(command.downloadSizeMb ?? null);
      if (normalizedDownloadSizeMb) {
        return `DOWNLOAD_DATA (${normalizedDownloadSizeMb} MB)`;
      }
      return "DOWNLOAD_DATA";
    }

    if (command.type === "START_SCREEN_MIRROR") {
      return "START_SCREEN_MIRROR";
    }

    if (command.type === "STOP_SCREEN_MIRROR") {
      return "STOP_SCREEN_MIRROR";
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

  const onEnableScreenMirroring = async () => {
    try {
      const result = await requestScreenMirrorPermission();
      await refreshScreenMirrorState();
      if (result.success) {
        setStatusMessage("Screen mirror permission enabled");
      } else {
        setStatusMessage(`Screen mirror permission failed: ${result.message}`);
      }
    } catch (error) {
      logEvent("enable_screen_mirror_permission_failed", { error });
      setStatusMessage("Failed to enable screen mirror permission");
    }
  };

  const onStopScreenMirroring = async () => {
    try {
      const result = await stopScreenMirroring("stopped_by_user");
      await refreshScreenMirrorState();
      if (result.success) {
        setStatusMessage("Screen sharing stopped");
      } else {
        setStatusMessage(`Stop sharing failed: ${result.message}`);
      }
    } catch (error) {
      logEvent("stop_screen_mirroring_failed", { error });
      setStatusMessage("Failed to stop screen sharing");
    }
  };

  useEffect(() => {
    introOverlayTimerRef.current = setTimeout(() => {
      Animated.timing(introOverlayOpacity, {
        toValue: 0,
        duration: INTRO_SCREEN_FADE_MS,
        useNativeDriver: true,
      }).start(({ finished }) => {
        if (finished) {
          setShowIntroOverlay(false);
        }
      });
    }, INTRO_SCREEN_DURATION_MS);

    return () => {
      if (introOverlayTimerRef.current) {
        clearTimeout(introOverlayTimerRef.current);
        introOverlayTimerRef.current = null;
      }
      introOverlayOpacity.stopAnimation();
    };
  }, [introOverlayOpacity]);

  useEffect(() => {
    const subscription = AppState.addEventListener("change", (nextState) => {
      if (nextState === "active") {
        void refreshStatus();
      }
    });

    return () => {
      subscription.remove();
    };
  }, []);

  useEffect(() => {
    let heartbeatTimer: ReturnType<typeof setInterval>;
    let pollTimer: ReturnType<typeof setInterval>;
    let statusTimer: ReturnType<typeof setInterval>;
    let pendingScreenMirrorTimer: ReturnType<typeof setInterval>;

    const init = async () => {
      await requestAndroidPermissions();
      await refreshDeviceIdentity();
      await refreshStatus();
      await refreshInAppWebViewState();
      await refreshScreenMirrorState();
      await processPendingScreenMirrorStartCommand();
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
        void refreshScreenMirrorState();
      }, 3000);

      pendingScreenMirrorTimer = setInterval(() => {
        void processPendingScreenMirrorStartCommand();
      }, 1000);
    };

    void init();

    return () => {
      if (heartbeatTimer) clearInterval(heartbeatTimer);
      if (pollTimer) clearInterval(pollTimer);
      if (statusTimer) clearInterval(statusTimer);
      if (pendingScreenMirrorTimer) clearInterval(pendingScreenMirrorTimer);
      if (downloadSuccessBannerTimeoutRef.current) {
        clearTimeout(downloadSuccessBannerTimeoutRef.current);
        downloadSuccessBannerTimeoutRef.current = null;
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <ImageBackground
      source={require("../assets/images/android-icon-background.png")}
      style={styles.screen}
      resizeMode="cover"
    >
      <StatusBar
        hidden={showIntroOverlay}
        barStyle="light-content"
        backgroundColor="#012065"
      />
      <View style={styles.headerLogoShell}>
        <Image
          source={require("../assets/images/header.png")}
          style={styles.headerLogo}
          resizeMode="cover"
        />
      </View>
      {downloadSuccessBannerVisible ? (
        <View style={styles.successBanner}>
          <Text style={styles.successBannerText}>{DOWNLOAD_DATA_SUCCESS_MESSAGE}</Text>
        </View>
      ) : null}
      <View style={styles.card}>
        <Text style={styles.title}>AutoCall</Text>
        <Text style={styles.subtitle}>Personal Android Call Control</Text>

        <View style={styles.section}>
          <View style={styles.rowBetween}>
            <Text style={styles.label}>Auto Answer</Text>
            <Switch
              value={uiState.autoAnswerEnabled}
              onValueChange={onToggleAutoAnswer}
              thumbColor={uiState.autoAnswerEnabled ? "#4BF5FF" : "#FFFFFF"}
              trackColor={{
                false: "rgba(255,255,255,0.22)",
                true: "rgba(16,97,255,0.76)",
              }}
              ios_backgroundColor="rgba(255,255,255,0.24)"
            />
          </View>

          <Text style={styles.label}>Auto Hangup Seconds</Text>
          <View style={styles.row}>
            <TextInput
              value={autoHangupSecondsText}
              onChangeText={setAutoHangupSecondsText}
              keyboardType="number-pad"
              style={[styles.input , styles.secondsInput]}
              placeholder="20"
              placeholderTextColor="rgba(255,255,255,0.45)"
            />
            <Pressable
              style={({ pressed }) => [styles.secondaryButton, pressed && styles.buttonPressed]}
              onPress={onApplyHangupSeconds}
              android_ripple={{ color: "rgba(75,245,255,0.18)" }}
            >
              <Text style={styles.secondaryButtonText}>Apply</Text>
            </Pressable>
          </View>
        </View>

        <View style={styles.section}>
          <View style={styles.row}>
            <Pressable
              style={({ pressed }) => [styles.primaryButton, pressed && styles.buttonPressed]}
              onPress={onEnableScreenMirroring}
              android_ripple={{ color: "rgba(75,245,255,0.18)" }}
            >
              <Text style={styles.primaryButtonText}>Enable Screen Mirroring</Text>
            </Pressable>
          </View>
        </View>


        <View style={styles.statusBox}>
          <Text style={styles.statusLine}>
            Device UID: {(deviceIdentity.deviceUid || "--").toUpperCase()}
          </Text>
          <Text style={styles.statusLine}>Device Name: {deviceIdentity.deviceName || "--"}</Text>
          <Text style={styles.statusLine}>
            Permission: {screenMirrorState.permissionGranted ? "GRANTED" : "NOT GRANTED"}
          </Text>
        </View>
      </View>
      {showIntroOverlay ? (
        <Animated.View style={[styles.introOverlay, { opacity: introOverlayOpacity }]}>
          <ImageBackground
            source={require("../assets/images/StartImage.png")}
            style={styles.introOverlayImage}
            resizeMode="cover"
          />
        </Animated.View>
      ) : null}
    </ImageBackground>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: "#000050",
    justifyContent: "center",
    alignItems: "center",
    padding: 16,
    experimental_backgroundImage:
      "linear-gradient(160deg, rgba(0,0,80,0.56) 0%, rgba(0,0,40,0.48) 45%, rgba(0,0,24,0.62) 100%)",
  },
  introOverlay: {
    ...StyleSheet.absoluteFillObject,
    zIndex: 999,
    backgroundColor: "#012065",
  },
  introOverlayImage: {
    flex: 1,
    width: "100%",
    height: "100%",
    backgroundColor: "#012065",
  },
  successBanner: {
    position: "absolute",
    top: 20,
    left: 16,
    right: 16,
    zIndex: 20,
    backgroundColor: "rgba(8,20,82,0.76)",
    experimental_backgroundImage:
      "linear-gradient(136deg, rgba(75,245,255,0.2) 0%, rgba(16,97,255,0.3) 56%, rgba(255,255,255,0.08) 100%)",
    borderColor: "rgba(75,245,255,0.64)",
    borderWidth: 1,
    borderRadius: 18,
    paddingVertical: 10,
    paddingHorizontal: 12,
    overflow: "hidden",
    shadowColor: "#4BF5FF",
    shadowOffset: { width: 0, height: 8 },
    shadowOpacity: 0.34,
    shadowRadius: 18,
    elevation: 12,
    boxShadow: [
      {
        offsetX: 0,
        offsetY: 8,
        blurRadius: 24,
        color: "rgba(0,0,80,0.52)",
      },
      {
        offsetX: 0,
        offsetY: 0,
        blurRadius: 0,
        spreadDistance: 1,
        color: "rgba(75,245,255,0.38)",
        inset: true,
      },
    ],
  },
  headerLogoShell: {
    position: "absolute",
    top: "5%",
    left: 16,
    zIndex: 30,
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    padding: 6,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: "rgba(255, 255, 255, 0.28)",
    backgroundColor: "rgba(255,255,255,0.08)",
    experimental_backgroundImage:
      "linear-gradient(145deg, rgba(255, 255, 255, 0.2), rgba(255, 255, 255, 0.06))",
    shadowColor: "#04081A",
    shadowOffset: { width: 0, height: 10 },
    shadowOpacity: 0.36,
    shadowRadius: 24,
    elevation: 12,
    boxShadow: [
      {
        offsetX: 0,
        offsetY: 10,
        blurRadius: 24,
        color: "rgba(4, 8, 26, 0.36)",
      },
    ],
  },
  headerLogo: {
    width: 56,
    height: 56,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: "rgba(255, 255, 255, 0.35)",
    shadowColor: "#000000",
    shadowOffset: { width: 0, height: 6 },
    shadowOpacity: 0.24,
    shadowRadius: 14,
    elevation: 8,
    boxShadow: [
      {
        offsetX: 0,
        offsetY: 6,
        blurRadius: 14,
        color: "rgba(0, 0, 0, 0.24)",
      },
    ],
  },
  successBannerText: {
    color: "#FFFFFF",
    fontSize: 13,
    fontWeight: "700",
    textAlign: "center",
    textShadowColor: "rgba(75,245,255,0.4)",
    textShadowOffset: { width: 0, height: 1 },
    textShadowRadius: 7,
  },
  card: {
    width: "100%",
    maxWidth: 460,
    backgroundColor: "rgba(10, 18, 48, 0.44)",
    experimental_backgroundImage:
      "linear-gradient(150deg, rgba(255,255,255,0.12) 0%, rgba(16,97,255,0.22) 42%, rgba(8,14,52,0.5) 100%), radial-gradient(circle at 8% 0%, rgba(255,255,255,0.38) 0%, rgba(255,255,255,0) 58%), linear-gradient(90deg, rgba(255,255,255,0) 0%, rgba(255,255,255,0.26) 24%, rgba(255,255,255,0.12) 58%, rgba(255,255,255,0) 100%)",
    borderRadius: 28,
    padding: 18,
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.22)",
    overflow: "hidden",
    shadowColor: "#000050",
    shadowOffset: { width: 0, height: 14 },
    shadowOpacity: 0.36,
    shadowRadius: 24,
    elevation: 18,
    boxShadow: [
      {
        offsetX: 0,
        offsetY: 22,
        blurRadius: 58,
        color: "rgba(0,0,80,0.72)",
      },
      {
        offsetX: 0,
        offsetY: 0,
        blurRadius: 0,
        spreadDistance: 1,
        color: "rgba(255,255,255,0.18)",
        inset: true,
      },
      {
        offsetX: 0,
        offsetY: -1,
        blurRadius: 0,
        color: "rgba(255,255,255,0.24)",
        inset: true,
      },
    ],
    borderBottomColor: "rgba(255,255,255,0.22)",
  },
  title: {
    fontSize: 26,
    fontWeight: "800",
    letterSpacing: 0.4,
    color: "#FFFFFF",
    textShadowColor: "rgba(75,245,255,0.32)",
    textShadowOffset: { width: 0, height: 2 },
    textShadowRadius: 12,
  },
  subtitle: {
    marginTop: 4,
    marginBottom: 12,
    fontSize: 14,
    color: "rgba(255,255,255,0.72)",
    letterSpacing: 0.2,
  },
  section: {
    marginTop: 12,
    padding: 13,
    borderRadius: 20,
    backgroundColor: "rgba(12, 19, 52, 0.42)",
    experimental_backgroundImage:
      "linear-gradient(150deg, rgba(255,255,255,0.1) 0%, rgba(16,97,255,0.18) 42%, rgba(8,14,52,0.44) 100%), radial-gradient(circle at 8% 0%, rgba(255,255,255,0.28) 0%, rgba(255,255,255,0) 56%)",
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.2)",
    overflow: "hidden",
    shadowColor: "#000050",
    shadowOffset: { width: 0, height: 10 },
    shadowOpacity: 0.28,
    shadowRadius: 16,
    elevation: 90,
    boxShadow: [
      {
        offsetX: 0,
        offsetY: 10,
        blurRadius: 26,
        color: "rgba(0,0,80,0.45)",
      },
      {
        offsetX: 0,
        offsetY: 0,
        blurRadius: 0,
        spreadDistance: 1,
        color: "rgba(255,255,255,0.16)",
        inset: true,
      },
    ],
    borderBottomColor: "rgba(255,255,255,0.22)",
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
    color: "#FFFFFF",
    letterSpacing: 0.2,
  },
  durationLabel: {
    marginTop: 10,
  },
  helperText: {
    marginTop: 6,
    fontSize: 12,
    color: "rgba(255,255,255,0.62)",
    lineHeight: 18,
  },
  input: {
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.24)",
    borderRadius: 14,
    paddingHorizontal: 10,
    paddingVertical: 9,
    color: "#FFFFFF",
    backgroundColor: "rgba(7, 14, 44, 0.5)",
    experimental_backgroundImage:
      "linear-gradient(145deg, rgba(255,255,255,0.1) 0%, rgba(16,97,255,0.12) 40%, rgba(0,0,80,0.42) 100%)",
    shadowColor: "#1061FF",
    shadowOffset: { width: 0, height: 8 },
    shadowOpacity: 0.24,
    shadowRadius: 12,
    elevation: 90,
    boxShadow: [
      {
        offsetX: 0,
        offsetY: 8,
        blurRadius: 18,
        color: "rgba(0,0,80,0.38)",
      },
      {
        offsetX: 0,
        offsetY: 0,
        blurRadius: 0,
        spreadDistance: 1,
        color: "rgba(255,255,255,0.18)",
        inset: true,
      },
    ],
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
    borderRadius: 14,
    backgroundColor: "rgba(10,48,182,0.6)",
    experimental_backgroundImage:
      "linear-gradient(135deg, rgba(75,245,255,0.36) 0%, rgba(16,97,255,0.9) 52%, rgba(0,0,80,0.78) 100%), radial-gradient(circle at 0% 0%, rgba(255,255,255,0.24) 0%, rgba(0,0,0,0) 56%)",
    paddingVertical: 10,
    alignItems: "center",
    borderWidth: 1,
    borderColor: "rgba(75,245,255,0.58)",
    overflow: "hidden",
    shadowColor: "#4BF5FF",
    shadowOffset: { width: 0, height: 10 },
    shadowOpacity: 0.34,
    shadowRadius: 18,
    elevation: 10,
    boxShadow: [
      {
        offsetX: 0,
        offsetY: 12,
        blurRadius: 22,
        color: "rgba(16,97,255,0.5)",
      },
      {
        offsetX: 0,
        offsetY: 0,
        blurRadius: 0,
        spreadDistance: 1,
        color: "rgba(75,245,255,0.42)",
        inset: true,
      },
    ],
  },
  primaryButtonText: {
    color: "#FFFFFF",
    fontWeight: "700",
    textShadowColor: "rgba(75,245,255,0.32)",
    textShadowOffset: { width: 0, height: 1 },
    textShadowRadius: 8,
  },
  secondaryButton: {
    borderRadius: 14,
    backgroundColor: "rgba(8,28,112,0.58)",
    experimental_backgroundImage:
      "linear-gradient(135deg, rgba(255,255,255,0.2) 0%, rgba(16,97,255,0.7) 46%, rgba(0,0,80,0.72) 100%)",
    paddingVertical: 10,
    paddingHorizontal: 14,
    alignItems: "center",
    borderWidth: 1,
    borderColor: "rgba(75,245,255,0.5)",
    overflow: "hidden",
    shadowColor: "#1061FF",
    shadowOffset: { width: 0, height: 8 },
    shadowOpacity: 0.28,
    shadowRadius: 14,
    elevation: 8,
    boxShadow: [
      {
        offsetX: 0,
        offsetY: 8,
        blurRadius: 20,
        color: "rgba(0,0,80,0.4)",
      },
      {
        offsetX: 0,
        offsetY: 0,
        blurRadius: 0,
        spreadDistance: 1,
        color: "rgba(75,245,255,0.35)",
        inset: true,
      },
    ],
  },
  secondaryButtonText: {
    color: "#FFFFFF",
    fontWeight: "700",
    textShadowColor: "rgba(75,245,255,0.26)",
    textShadowOffset: { width: 0, height: 1 },
    textShadowRadius: 7,
  },
  buttonPressed: {
    transform: [{ scale: 0.985 }],
    opacity: 0.94,
  },
  dangerButton: {
    flex: 1,
    borderRadius: 14,
    backgroundColor: "rgba(80,18,15,0.72)",
    experimental_backgroundImage:
      "linear-gradient(140deg, rgba(255,90,60,0.42) 0%, rgba(86,18,16,0.78) 100%)",
    paddingVertical: 10,
    alignItems: "center",
    borderWidth: 1,
    borderColor: "rgba(255,90,60,0.62)",
  },
  dangerButtonText: {
    color: "#FFFFFF",
    fontWeight: "700",
  },
  statusBox: {
    marginTop: 12,
    borderRadius: 20,
    backgroundColor: "rgba(10, 18, 50, 0.4)",
    experimental_backgroundImage:
      "linear-gradient(150deg, rgba(255,255,255,0.1) 0%, rgba(16,97,255,0.16) 40%, rgba(0,0,80,0.45) 100%), radial-gradient(circle at 8% 0%, rgba(255,255,255,0.3) 0%, rgba(255,255,255,0) 56%)",
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.2)",
    padding: 12,
    overflow: "hidden",
    shadowColor: "#000050",
    shadowOffset: { width: 0, height: 10 },
    shadowOpacity: 0.26,
    shadowRadius: 16,
    elevation: 8,
    boxShadow: [
      {
        offsetX: 0,
        offsetY: 10,
        blurRadius: 24,
        color: "rgba(0,0,80,0.42)",
      },
      {
        offsetX: 0,
        offsetY: 0,
        blurRadius: 0,
        spreadDistance: 1,
        color: "rgba(255,255,255,0.16)",
        inset: true,
      },
    ],
    borderBottomColor: "rgba(255,255,255,0.22)",
  },
  statusLine: {
    color: "#FFFFFF",
    fontSize: 16,
    lineHeight: 22,
    fontWeight: "600",
    marginBottom: 4,
    textShadowColor: "rgba(16,97,255,0.24)",
    textShadowOffset: { width: 0, height: 1 },
    textShadowRadius: 5,
  },
  statusMessage: {
    color: "rgba(75,245,255,0.92)",
    fontSize: 13,
    marginTop: 8,
    fontWeight: "600",
  },
});
