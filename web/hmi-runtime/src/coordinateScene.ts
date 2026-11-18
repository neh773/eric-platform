import { createContext } from "react";

/** One component in a coordinate container, as a pipe needs it to find a port. */
export interface SceneChild {
  id: string;
  type: string;
  name: string;
  frame?: { x: number; y: number; width: number; height: number };
  props: Record<string, unknown>;
}

/** Sibling frames for the coordinate container currently drawing. Pipes read it to terminate on ports. */
export interface CoordinateSceneValue {
  width: number;
  height: number;
  children: SceneChild[];
}

export const CoordinateScene = createContext<CoordinateSceneValue | null>(null);
