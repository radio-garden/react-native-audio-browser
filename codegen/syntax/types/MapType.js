export class MapType {
    get canBePassedByReference() {
        // It's a shared_ptr<..>, no ref.
        return true;
    }
    get kind() {
        return 'map';
    }
    getCode(language) {
        switch (language) {
            case 'c++':
                return 'std::shared_ptr<AnyMap>';
            case 'swift':
                return 'AnyMap';
            case 'kotlin':
                return 'AnyMap';
            default:
                throw new Error(`Language ${language} is not yet supported for MapType!`);
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
                    name: 'NitroModules/AnyMap.hpp',
                    language: 'c++',
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
                    name: 'com.margelo.nitro.core.AnyMap',
                    language: 'kotlin',
                    space: 'system',
                });
                break;
        }
        return imports;
    }
}
