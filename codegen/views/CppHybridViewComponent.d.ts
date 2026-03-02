import type { SourceFile } from '../syntax/SourceFile.js';
import type { HybridObjectSpec } from '../syntax/HybridObjectSpec.js';
interface ViewComponentNames {
    propsClassName: `${string}Props`;
    stateClassName: `${string}State`;
    nameVariable: `${string}ComponentName`;
    shadowNodeClassName: `${string}ShadowNode`;
    descriptorClassName: `${string}ComponentDescriptor`;
    component: `${string}Component`;
    manager: `${string}Manager`;
}
export declare function getViewComponentNames(spec: HybridObjectSpec): ViewComponentNames;
export declare function createViewComponentShadowNodeFiles(spec: HybridObjectSpec): SourceFile[];
export {};
