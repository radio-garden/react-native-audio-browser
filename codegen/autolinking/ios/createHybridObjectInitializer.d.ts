import type { SourceFile } from '../../syntax/SourceFile.js';
type ObjcFile = Omit<SourceFile, 'language'> & {
    language: 'objective-c++';
};
type SwiftFile = Omit<SourceFile, 'language'> & {
    language: 'swift';
};
export declare function createHybridObjectIntializer(): [ObjcFile, SwiftFile] | [];
export {};
