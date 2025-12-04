import {} from '../SourceFile.js';
export class RecordType {
    keyType;
    valueType;
    constructor(keyType, valueType) {
        this.keyType = keyType;
        this.valueType = valueType;
    }
    get canBePassedByReference() {
        // It's a unordered_map<..>, heavy to copy.
        return true;
    }
    get kind() {
        return 'record';
    }
    getCode(language, options) {
        const keyCode = this.keyType.getCode(language, options);
        const valueCode = this.valueType.getCode(language, options);
        switch (language) {
            case 'c++':
                return `std::unordered_map<${keyCode}, ${valueCode}>`;
            case 'swift':
                return `Dictionary<${keyCode}, ${valueCode}>`;
            case 'kotlin':
                return `Map<${keyCode}, ${valueCode}>`;
            default:
                throw new Error(`Language ${language} is not yet supported for RecordType!`);
        }
    }
    getExtraFiles(visited) {
        return [...this.keyType.getExtraFiles(visited), ...this.valueType.getExtraFiles(visited)];
    }
    getRequiredImports(language, visited) {
        const imports = [
            ...this.keyType.getRequiredImports(language, visited),
            ...this.valueType.getRequiredImports(language, visited),
        ];
        if (language === 'c++') {
            imports.push({
                language: 'c++',
                name: 'unordered_map',
                space: 'system',
            });
        }
        return imports;
    }
}
