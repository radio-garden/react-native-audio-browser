export class CustomType {
    typeConfig;
    typeName;
    constructor(typeName, typeConfig) {
        this.typeName = typeName;
        this.typeConfig = typeConfig;
    }
    get canBePassedByReference() {
        return this.typeConfig.canBePassedByReference ?? false;
    }
    get kind() {
        return 'custom-type';
    }
    getCode(language) {
        switch (language) {
            case 'c++':
                return this.typeName;
            default:
                throw new Error(`Language ${language} is not yet supported for CustomType "${this.typeName}"!`);
        }
    }
    getExtraFiles() {
        return [];
    }
    getRequiredImports(language) {
        const imports = [];
        if (language === 'c++') {
            imports.push({
                name: this.typeConfig.include,
                language: 'c++',
                space: 'user',
            });
        }
        return imports;
    }
}
