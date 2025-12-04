export class ArrayBufferType {
    get canBePassedByReference() {
        // It's a shared_ptr.
        return true;
    }
    get kind() {
        return 'array-buffer';
    }
    getCode(language) {
        switch (language) {
            case 'c++':
                return 'std::shared_ptr<ArrayBuffer>';
            case 'swift':
                return 'ArrayBuffer';
            case 'kotlin':
                return 'ArrayBuffer';
            default:
                throw new Error(`Language ${language} is not yet supported for ArrayBufferType!`);
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
                    name: 'NitroModules/ArrayBuffer.hpp',
                    space: 'system',
                });
                break;
            case 'swift':
                imports.push({
                    name: 'NitroModules',
                    language: 'swift',
                    space: 'system',
                });
                break;
            case 'kotlin':
                imports.push({
                    name: 'com.margelo.nitro.core.ArrayBuffer',
                    language: 'kotlin',
                    space: 'system',
                });
                break;
        }
        return imports;
    }
}
