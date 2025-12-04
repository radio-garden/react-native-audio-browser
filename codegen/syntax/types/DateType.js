export class DateType {
    get canBePassedByReference() {
        // simple chrono value type
        return false;
    }
    get kind() {
        return 'date';
    }
    getCode(language) {
        switch (language) {
            case 'c++':
                return 'std::chrono::system_clock::time_point';
            case 'swift':
                return 'Date';
            case 'kotlin':
                return 'java.time.Instant';
            default:
                throw new Error(`Language ${language} is not yet supported for DateType!`);
        }
    }
    getExtraFiles() {
        return [];
    }
    getRequiredImports(language) {
        const imports = [];
        if (language === 'c++') {
            imports.push({
                name: 'chrono',
                language: 'c++',
                space: 'system',
            });
        }
        return imports;
    }
}
