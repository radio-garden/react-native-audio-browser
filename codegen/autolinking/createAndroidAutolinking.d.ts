import type { SourceFile, SourceImport } from '../syntax/SourceFile.js';
import type { Autolinking } from './Autolinking.js';
interface JNIHybridRegistration {
    sourceImport: SourceImport;
    registrationCode: string;
}
interface AndroidAutolinking extends Autolinking {
    jniHybridRegistrations: JNIHybridRegistration[];
}
export declare function createAndroidAutolinking(allFiles: SourceFile[]): AndroidAutolinking;
export {};
