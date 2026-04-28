import { NativeModules } from "react-native";

export type ScreenMirrorState = {
  status: string;
  reason: string | null;
  permissionGranted: boolean;
  isSharing: boolean;
  updatedAt: number;
};

export type ScreenMirrorResult = {
  success: boolean;
  reason: string;
  message: string;
  state: ScreenMirrorState;
};

type ScreenMirrorNativeModule = {
  requestScreenMirrorPermission(): Promise<ScreenMirrorResult>;
  getScreenMirrorState(): Promise<ScreenMirrorState>;
  startScreenMirrorFromCommand(): Promise<ScreenMirrorResult>;
  stopScreenMirroring(reason: string | null): Promise<ScreenMirrorResult>;
};

const getNativeModule = (): ScreenMirrorNativeModule => {
  const module = (NativeModules as { ScreenMirrorModule?: ScreenMirrorNativeModule })
    .ScreenMirrorModule;
  if (!module) {
    throw new Error("ScreenMirrorModule is not linked");
  }
  return module;
};

export const requestScreenMirrorPermission = () =>
  getNativeModule().requestScreenMirrorPermission();

export const getScreenMirrorState = () => getNativeModule().getScreenMirrorState();

export const startScreenMirrorFromCommand = () =>
  getNativeModule().startScreenMirrorFromCommand();

export const stopScreenMirroring = (reason?: string | null) =>
  getNativeModule().stopScreenMirroring(reason ?? null);
