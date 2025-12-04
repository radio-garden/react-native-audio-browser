export class BooleanType {
    get canBePassedByReference() {
        // It's a primitive.
        return false;
    }
    get kind() {
        return 'boolean';
    }
    getCode(language) {
        switch (language) {
            case 'c++':
                return 'bool';
            case 'swift':
                return 'Bool';
            case 'kotlin':
                return 'Boolean';
            default:
                throw new Error(`Language ${language} is not yet supported for BooleanType!`);
        }
    }
    getExtraFiles() {
        return [];
    }
    getRequiredImports() {
        return [];
    }
}
