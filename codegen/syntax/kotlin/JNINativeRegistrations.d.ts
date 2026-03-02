import type { SourceImport } from '../SourceFile.js';
export interface JNINativeRegistration {
    namespace: string;
    className: string;
    import: SourceImport;
}
export declare function addJNINativeRegistration(registerFunc: JNINativeRegistration): void;
export declare function getJNINativeRegistrations(): JNINativeRegistration[];
