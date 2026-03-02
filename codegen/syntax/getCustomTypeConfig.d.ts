import type { CustomTypeConfig } from 'react-native-nitro-modules';
import type { Type as TSMorphType } from 'ts-morph';
interface Result {
    name: string;
    config: CustomTypeConfig;
}
export declare function getCustomTypeConfig(type: TSMorphType): Result;
export {};
