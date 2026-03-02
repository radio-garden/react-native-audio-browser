import type { Type } from './Type.js';
export declare function getTypeAs<T>(type: Type, classReference: new (...args: any[]) => T): T;
