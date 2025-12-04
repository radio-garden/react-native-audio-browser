import { getForwardDeclaration } from '../c++/getForwardDeclaration.js';
export class AnyHybridObjectType {
    constructor() { }
    get canBePassedByReference() {
        // It's a shared_ptr<..>, no copy.
        return true;
    }
    get kind() {
        return 'hybrid-object-base';
    }
    getCode(language) {
        switch (language) {
            case 'c++':
                return `std::shared_ptr<HybridObject>`;
            default:
                throw new Error(`\`AnyHybridObject\` cannot be used directly in ${language} yet. Use a specific derived class of \`HybridObject\` instead!`);
        }
    }
    getExtraFiles() {
        return [];
    }
    getRequiredImports(language) {
        const imports = [];
        switch (language) {
            case 'c++':
                imports.push({
                    language: 'c++',
                    name: 'memory',
                    space: 'system',
                }, {
                    name: `NitroModules/HybridObject.hpp`,
                    forwardDeclaration: getForwardDeclaration('class', 'HybridObject', 'margelo::nitro'),
                    language: 'c++',
                    space: 'system',
                });
                break;
        }
        return imports;
    }
}
