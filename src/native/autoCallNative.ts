import { NativeModules } from "react-native";

export type AutoAnswerStatus = {
  enabled: boolean;
  autoHangupSeconds: number;
  hangupScheduled: boolean;
  lastEvent: string;
  lastEventAt: number;
};

export type DeviceIdentity = {
  deviceUid: string;
  deviceName: string;
};

export type InAppWebViewState = {
  isOpen: boolean;
  currentUrl: string | null;
};

type InAppWebViewCommandResult = {
  success: boolean;
  reason: string;
  message: string;
  url: string | null;
  replacedExisting: boolean;
  closed: boolean;
  noOp: boolean;
  isOpen: boolean;
  currentUrl: string | null;
};

type OutgoingCallResult = {
  action: string;
  phoneNumber: string;
  usedCurrentActivity: boolean;
  timestamp: number;
};

type SimpleCallResult = {
  success: boolean;
  reason: string;
  message: string;
  phoneNumber: string | null;
  autoEndMs: number | null;
  durationSeconds?: number | null;
  timestamp: number;
};

type SimpleSmsResult = {
  success: boolean;
  reason: string;
  message: string;
  phoneNumber: string | null;
  textLength: number | null;
  timestamp: number;
};

type AutoCallNativeModule = {
  startOutgoingCall(phoneNumber: string): Promise<OutgoingCallResult>;
  startSimpleCall(phoneNumber: string, autoEndMs: number | null): Promise<SimpleCallResult>;
  startServerCommandCall(phoneNumber: string, durationSeconds: number | null): Promise<SimpleCallResult>;
  startServerCommandSms(phoneNumber: string, message: string): Promise<SimpleSmsResult>;
  enableAutoAnswer(config: { autoHangupSeconds: number }): Promise<AutoAnswerStatus>;
  disableAutoAnswer(): Promise<AutoAnswerStatus>;
  getAutoAnswerStatus(): Promise<AutoAnswerStatus>;
  getDeviceIdentity(): Promise<DeviceIdentity>;
  endCurrentCall(): Promise<{ ended: boolean }>;
  openInAppWebView(url: string): Promise<InAppWebViewCommandResult>;
  closeInAppWebView(): Promise<InAppWebViewCommandResult>;
  getInAppWebViewState(): Promise<InAppWebViewState>;
};

const getNativeModule = (): AutoCallNativeModule => {
  const module = (NativeModules as { AutoCallNative?: AutoCallNativeModule }).AutoCallNative;
  if (!module) {
    throw new Error("AutoCallNative module is not linked");
  }
  return module;
};

export const placeCall = (phoneNumber: string) => getNativeModule().startOutgoingCall(phoneNumber);

export const startSimpleCall = (phoneNumber: string, autoEndMs?: number | null) =>
  getNativeModule().startSimpleCall(phoneNumber, autoEndMs ?? null);

export const startServerCommandCall = (phoneNumber: string, durationSeconds?: number | null) =>
  getNativeModule().startServerCommandCall(phoneNumber, durationSeconds ?? null);

export const startServerCommandSms = (phoneNumber: string, message: string) =>
  getNativeModule().startServerCommandSms(phoneNumber, message);

export const enableAutoAnswer = (autoHangupSeconds: number) =>
  getNativeModule().enableAutoAnswer({ autoHangupSeconds });

export const disableAutoAnswer = () => getNativeModule().disableAutoAnswer();

export const getStatus = () => getNativeModule().getAutoAnswerStatus();

export const getDeviceIdentity = () => getNativeModule().getDeviceIdentity();

export const endCurrentCall = async () => {
  return getNativeModule().endCurrentCall();
};

export const openInAppWebView = (url: string) => getNativeModule().openInAppWebView(url);

export const closeInAppWebView = () => getNativeModule().closeInAppWebView();

export const getInAppWebViewState = () => getNativeModule().getInAppWebViewState();
