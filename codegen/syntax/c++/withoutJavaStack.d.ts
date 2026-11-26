import type { SourceImport } from '../SourceFile.js';
export declare const JAVA_ERROR_IMPORT: SourceImport;
export declare function withoutJavaStack(throwable: string): string;
export declare function callJava(method: string, args: string[]): string;
