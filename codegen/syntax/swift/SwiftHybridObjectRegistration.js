import { NitroConfig } from '../../config/NitroConfig.js';
import { indent } from '../../utils.js';
import { getHybridObjectName } from '../getHybridObjectName.js';
import { HybridObjectType } from '../types/HybridObjectType.js';
import { SwiftCxxBridgedType } from './SwiftCxxBridgedType.js';
export function getAutolinkingNamespace() {
    const swiftNamespace = NitroConfig.current.getIosModuleName();
    const autolinkingClassName = `${swiftNamespace}Autolinking`;
    return `${swiftNamespace}::${autolinkingClassName}`;
}
export function getHybridObjectConstructorCall(hybridObjectName) {
    const namespace = getAutolinkingNamespace();
    return `${namespace}::create${hybridObjectName}();`;
}
export function getIsRecyclableCall(hybridObjectName) {
    const namespace = getAutolinkingNamespace();
    return `${namespace}::is${hybridObjectName}Recyclable();`;
}
export function createSwiftHybridObjectRegistration({ hybridObjectName, swiftClassName, }) {
    const { HybridTSpecSwift } = getHybridObjectName(hybridObjectName);
    const type = new HybridObjectType(hybridObjectName, 'swift', [], NitroConfig.current);
    const bridge = new SwiftCxxBridgedType(type);
    return {
        swiftRegistrationMethods: `
public static func create${hybridObjectName}() -> ${bridge.getTypeCode('swift')} {
  let hybridObject = ${swiftClassName}()
  return ${indent(bridge.parseFromSwiftToCpp('hybridObject', 'swift'), '  ')}
}

public static func is${hybridObjectName}Recyclable() -> Bool {
  return ${swiftClassName}.self is any RecyclableView.Type
}
    `.trim(),
        requiredImports: [
            { name: `${HybridTSpecSwift}.hpp`, language: 'c++', space: 'user' },
        ],
        cppCode: `
HybridObjectRegistry::registerHybridObjectConstructor(
  "${hybridObjectName}",
  []() -> std::shared_ptr<HybridObject> {
    ${type.getCode('c++')} hybridObject = ${getHybridObjectConstructorCall(hybridObjectName)}
    return hybridObject;
  }
);
      `.trim(),
    };
}
