import { escapeCppName } from '../helpers.js';
export class NamedWrappingType {
    type;
    name;
    constructor(name, type) {
        this.name = name;
        this.type = type;
    }
    get escapedName() {
        return escapeCppName(this.name);
    }
    get kind() {
        return this.type.kind;
    }
    get canBePassedByReference() {
        return this.type.canBePassedByReference;
    }
    getCode(language, options) {
        return this.type.getCode(language, options);
    }
    getExtraFiles(visited) {
        return this.type.getExtraFiles(visited);
    }
    getRequiredImports(language, visited) {
        return this.type.getRequiredImports(language, visited);
    }
}
