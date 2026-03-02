import { z } from 'zod';
export declare const NitroUserConfigSchema: z.ZodObject<{
    cxxNamespace: z.ZodArray<z.ZodString>;
    ios: z.ZodObject<{
        iosModuleName: z.ZodString;
    }, z.core.$strip>;
    android: z.ZodObject<{
        androidNamespace: z.ZodArray<z.ZodString>;
        androidCxxLibName: z.ZodString;
    }, z.core.$strip>;
    autolinking: z.ZodRecord<z.ZodString, z.ZodObject<{
        cpp: z.ZodOptional<z.ZodString>;
        swift: z.ZodOptional<z.ZodString>;
        kotlin: z.ZodOptional<z.ZodString>;
    }, z.core.$strip>>;
    ignorePaths: z.ZodOptional<z.ZodArray<z.ZodString>>;
    gitAttributesGeneratedFlag: z.ZodDefault<z.ZodOptional<z.ZodBoolean>>;
}, z.core.$strip>;
/**
 * Represents the structure of a `nitro.json` config file.
 */
export type NitroUserConfig = z.infer<typeof NitroUserConfigSchema>;
