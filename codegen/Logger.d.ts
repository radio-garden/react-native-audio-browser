export type LogLevel = 'debug' | 'info' | 'warning' | 'error';
export declare function isValidLogLevel(level: unknown): level is LogLevel;
export declare function setLogLevel(level: LogLevel): void;
export declare const Logger: {
    withIndented(callback: () => void): void;
    debug(message: string, ...extra: unknown[]): void;
    info(message: string, ...extra: unknown[]): void;
    warn(message: string, ...extra: unknown[]): void;
    error(message: string, ...extra: unknown[]): void;
};
