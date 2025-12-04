import { NamedWrappingType } from './NamedWrappingType.js';
export function getTypeAs(type, classReference) {
    if (type instanceof classReference) {
        return type;
    }
    else if (type instanceof NamedWrappingType) {
        return getTypeAs(type.type, classReference);
    }
    else {
        throw new Error(`Type of kind "${type.kind}" is not a ${classReference}!`);
    }
}
