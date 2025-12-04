export class NullType {
    get canBePassedByReference() {
        // It's a primitive.
        return false;
    }
    get kind() {
        return 'null';
    }
    getCode(language) {
        switch (language) {
            case 'c++':
                return 'nitro::NullType';
            case 'swift':
                return 'NullType';
            case 'kotlin':
                return 'NullType';
            default:
                throw new Error(`Language ${language} is not yet supported for NullType!`);
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
                    language: language,
                    name: 'NitroModules/Null.hpp',
                    space: 'system',
                });
                break;
            case 'swift':
                imports.push({
                    language: language,
                    name: 'NitroModules',
                    space: 'system',
                });
                break;
            case 'kotlin':
                imports.push({
                    language: language,
                    name: 'com.margelo.nitro.core.NullType',
                    space: 'system',
                });
                break;
        }
        return imports;
    }
}
