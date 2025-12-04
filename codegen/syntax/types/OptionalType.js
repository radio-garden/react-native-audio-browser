import {} from '../SourceFile.js';
export class OptionalType {
    wrappingType;
    constructor(wrappingType) {
        this.wrappingType = wrappingType;
    }
    get canBePassedByReference() {
        // depends whether the wrapping type is heavy to copy or not.
        return this.wrappingType.canBePassedByReference;
    }
    get kind() {
        return 'optional';
    }
    get needsBraces() {
        switch (this.wrappingType.kind) {
            case 'function':
                return true;
            default:
                return false;
        }
    }
    getCode(language, options) {
        const wrapping = this.wrappingType.getCode(language, options);
        switch (language) {
            case 'c++':
                return `std::optional<${wrapping}>`;
            case 'swift':
                if (this.needsBraces) {
                    return `(${wrapping})?`;
                }
                else {
                    return `${wrapping}?`;
                }
            case 'kotlin':
                if (this.needsBraces) {
                    return `(${wrapping})?`;
                }
                else {
                    return `${wrapping}?`;
                }
            default:
                throw new Error(`Language ${language} is not yet supported for OptionalType!`);
        }
    }
    getExtraFiles(visited) {
        return this.wrappingType.getExtraFiles(visited);
    }
    getRequiredImports(language, visited) {
        const imports = this.wrappingType.getRequiredImports(language, visited);
        if (language === 'c++') {
            imports.push({
                language: 'c++',
                name: 'optional',
                space: 'system',
            });
        }
        return imports;
    }
}
