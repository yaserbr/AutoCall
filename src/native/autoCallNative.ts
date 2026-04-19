import { NativeModules } from "react-native";

export type AutoAnswerStatus = {
  enabled: boolean;
  autoHangupSeconds: number;
  hangupScheduled: boolean;
  lastEvent: string;
  lastEventAt: number;
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
  timestamp: number;
};

type AutoCallNativeModule = {
  startOutgoingCall(phoneNumber: string): Promise<OutgoingCallResult>;
  startSimpleCall(phoneNumber: string, autoEndMs: number | null): Promise<SimpleCallResult>;
  enableAutoAnswer(config: { autoHangupSeconds: number }): Promise<AutoAnswerStatus>;
  disableAutoAnswer(): Promise<AutoAnswerStatus>;
  getAutoAnswerStatus(): Promise<AutoAnswerStatus>;
  endCurrentCall(): Promise<{ ended: boolean }>;
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

export const enableAutoAnswer = (autoHangupSeconds: number) =>
  getNativeModule().enableAutoAnswer({ autoHangupSeconds });

export const disableAutoAnswer = () => getNativeModule().disableAutoAnswer();

export const getStatus = () => getNativeModule().getAutoAnswerStatus();

export const endCurrentCall = () => getNativeModule().endCurrentCall();
