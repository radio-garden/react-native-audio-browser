import { escapeCppName, isNotDuplicate } from '../helpers.js';
import {} from '../SourceFile.js';
export const VariantLabels = [
    'first',
    'second',
    'third',
    'fourth',
    'fifth',
    'sixth',
    'seventh',
    'eigth',
    'ninth',
    'tenth',
];
export class VariantType {
    variants;
    aliasName;
    constructor(variants, aliasName) {
        this.variants = variants;
        this.aliasName = aliasName;
    }
    get canBePassedByReference() {
        // It's a variant<..> - heavy to copy
        return true;
    }
    get kind() {
        return 'variant';
    }
    get jsType() {
        return this.variants.map((v) => v.kind).join(' | ');
    }
    get cases() {
        return this.variants.map((v, i) => {
            const label = VariantLabels[i];
            if (label == null)
                throw new Error(`Variant<...> (\`${this.jsType}\`) does not support ${i} cases!`);
            return [label, v];
        });
    }
    getAliasName(language, options) {
        if (this.aliasName == null) {
            const variants = this.variants.map((v) => v.getCode(language, options));
            return escapeCppName(`Variant_${variants.join('_')}`);
        }
        return this.aliasName;
    }
    getCode(language, options) {
        const types = this.variants
            .map((v) => v.getCode(language, options))
            .filter(isNotDuplicate);
        switch (language) {
            case 'c++':
                return `std::variant<${types.join(', ')}>`;
            case 'swift':
            case 'kotlin':
                return this.getAliasName(language, options);
            default:
                throw new Error(`Language ${language} is not yet supported for VariantType!`);
        }
    }
    getExtraFiles(visited) {
        return this.variants.flatMap((v) => v.getExtraFiles(visited));
    }
    getRequiredImports(language, visited) {
        const imports = this.variants.flatMap((v) => v.getRequiredImports(language, visited));
        if (language === 'c++') {
            imports.push({
                language: 'c++',
                name: 'variant',
                space: 'system',
            });
        }
        return imports;
    }
}
