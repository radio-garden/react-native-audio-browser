import { Type as TSMorphType } from 'ts-morph';
function isSymbol(type, symbolName) {
    // check the symbol directly
    const symbol = type.getSymbol();
    if (symbol?.getName() === symbolName) {
        return true;
    }
    // loop through the alias symbol alias chain to test each one
    let aliasSymbol = type.getAliasSymbol();
    while (aliasSymbol != null) {
        if (aliasSymbol.getName() === symbolName) {
            return true;
        }
        aliasSymbol = aliasSymbol.getAliasedSymbol();
    }
    // nothing found.
    return false;
}
export function isPromise(type) {
    return isSymbol(type, 'Promise');
}
export function isRecord(type) {
    return isSymbol(type, 'Record');
}
export function isArrayBuffer(type) {
    return isSymbol(type, 'ArrayBuffer');
}
export function isDate(type) {
    return isSymbol(type, 'Date');
}
export function isMap(type) {
    return isSymbol(type, 'AnyMap');
}
export function isError(type) {
    return isSymbol(type, 'Error');
}
export function isCustomType(type) {
    return (type.getProperty('__customTypeName') != null &&
        type.getProperty('__customTypeConfig') != null);
}
export function isSyncFunction(type) {
    if (type.getCallSignatures().length < 1)
        // not a function.
        return false;
    const syncTag = type.getProperty('__syncTag');
    return syncTag != null;
}
