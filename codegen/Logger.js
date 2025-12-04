const levelMap = {
    debug: 0,
    info: 1,
    warning: 2,
    error: 3,
};
let currentLogLevel = 'info';
export function isValidLogLevel(level) {
    // @ts-expect-error
    return typeof levelMap[level] === 'number';
}
export function setLogLevel(level) {
    currentLogLevel = level;
}
function isAtLeast(level) {
    return levelMap[level] >= levelMap[currentLogLevel];
}
let indentation = 0;
function getIndentation() {
    let string = '';
    for (let i = 0; i < indentation; i++) {
        string += '  ';
    }
    return string;
}
export const Logger = {
    withIndented(callback) {
        try {
            indentation++;
            callback();
        }
        finally {
            indentation--;
        }
    },
    debug(message, ...extra) {
        if (isAtLeast('debug')) {
            console.debug(getIndentation() + message, ...extra);
        }
    },
    info(message, ...extra) {
        if (isAtLeast('info')) {
            console.info(getIndentation() + message, ...extra);
        }
    },
    warn(message, ...extra) {
        if (isAtLeast('warning')) {
            console.warn(getIndentation() + message, ...extra);
        }
    },
    error(message, ...extra) {
        if (isAtLeast('error')) {
            console.error(getIndentation() + message, ...extra);
        }
    },
};
