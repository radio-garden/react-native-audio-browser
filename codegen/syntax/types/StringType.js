export class StringType {
    get canBePassedByReference() {
        // It's a string<..>, heavy to copy.
        return true;
    }
    get kind() {
        return 'string';
    }
    getCode(language) {
        switch (language) {
            case 'c++':
                return 'std::string';
            case 'swift':
                return 'String';
            case 'kotlin':
                return 'String';
            default:
                throw new Error(`Language ${language} is not yet supported for StringType!`);
        }
    }
    getExtraFiles() {
        return [];
    }
    getRequiredImports(language) {
        const imports = [];
        if (language === 'c++') {
            imports.push({
                language: 'c++',
                name: 'string',
                space: 'system',
            });
        }
        return imports;
    }
}
