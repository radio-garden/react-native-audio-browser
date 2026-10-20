import { z } from 'zod';
declare const autolinkingAllImplementationSchema: z.ZodObject<{
    language: z.ZodLiteral<"c++">;
    implementationClassName: z.ZodString;
}, z.core.$strip>;
declare const autolinkingIOSImplementationSchema: z.ZodObject<{
    language: z.ZodEnum<{
        "c++": "c++";
        swift: "swift";
    }>;
    implementationClassName: z.ZodString;
}, z.core.$strip>;
declare const autolinkingAndroidImplementationSchema: z.ZodObject<{
    language: z.ZodEnum<{
        "c++": "c++";
        kotlin: "kotlin";
    }>;
    implementationClassName: z.ZodString;
}, z.core.$strip>;
declare const autolinkingPlatformImplementationSchema: z.ZodObject<{
    language: z.ZodEnum<{
        "c++": "c++";
        swift: "swift";
        kotlin: "kotlin";
    }>;
    implementationClassName: z.ZodString;
}, z.core.$strip>;
declare const autolinkingHybridObjectSchema: z.ZodUnion<readonly [z.ZodObject<{
    all: z.ZodOptional<z.ZodObject<{
        language: z.ZodLiteral<"c++">;
        implementationClassName: z.ZodString;
    }, z.core.$strip>>;
    ios: z.ZodOptional<z.ZodObject<{
        language: z.ZodEnum<{
            "c++": "c++";
            swift: "swift";
        }>;
        implementationClassName: z.ZodString;
    }, z.core.$strip>>;
    android: z.ZodOptional<z.ZodObject<{
        language: z.ZodEnum<{
            "c++": "c++";
            kotlin: "kotlin";
        }>;
        implementationClassName: z.ZodString;
    }, z.core.$strip>>;
}, z.core.$catchall<z.ZodObject<{
    language: z.ZodEnum<{
        "c++": "c++";
        swift: "swift";
        kotlin: "kotlin";
    }>;
    implementationClassName: z.ZodString;
}, z.core.$strip>>>, z.ZodPipe<z.ZodObject<{
    cpp: z.ZodOptional<z.ZodString>;
    swift: z.ZodOptional<z.ZodString>;
    kotlin: z.ZodOptional<z.ZodString>;
}, z.core.$strict>, z.ZodTransform<{
    [x: string]: {
        language: "c++" | "swift" | "kotlin";
        implementationClassName: string;
    };
    all?: {
        language: "c++";
        implementationClassName: string;
    } | undefined;
    ios?: {
        language: "c++" | "swift";
        implementationClassName: string;
    } | undefined;
    android?: {
        language: "c++" | "kotlin";
        implementationClassName: string;
    } | undefined;
}, {
    cpp?: string | undefined;
    swift?: string | undefined;
    kotlin?: string | undefined;
}>>]>;
export type AutolinkingAllImplementation = z.infer<typeof autolinkingAllImplementationSchema>;
export type AutolinkingIOSImplementation = z.infer<typeof autolinkingIOSImplementationSchema>;
export type AutolinkingAndroidImplementation = z.infer<typeof autolinkingAndroidImplementationSchema>;
export type AutolinkingPlatformImplementation = z.infer<typeof autolinkingPlatformImplementationSchema>;
export type AutolinkingHybridObject = z.infer<typeof autolinkingHybridObjectSchema>;
export declare const NitroUserConfigSchema: z.ZodObject<{
    cxxNamespace: z.ZodArray<z.ZodString>;
    ios: z.ZodObject<{
        iosModuleName: z.ZodString;
    }, z.core.$strip>;
    android: z.ZodObject<{
        androidNamespace: z.ZodArray<z.ZodString>;
        androidCxxLibName: z.ZodString;
    }, z.core.$strip>;
    autolinking: z.ZodRecord<z.ZodString, z.ZodUnion<readonly [z.ZodObject<{
        all: z.ZodOptional<z.ZodObject<{
            language: z.ZodLiteral<"c++">;
            implementationClassName: z.ZodString;
        }, z.core.$strip>>;
        ios: z.ZodOptional<z.ZodObject<{
            language: z.ZodEnum<{
                "c++": "c++";
                swift: "swift";
            }>;
            implementationClassName: z.ZodString;
        }, z.core.$strip>>;
        android: z.ZodOptional<z.ZodObject<{
            language: z.ZodEnum<{
                "c++": "c++";
                kotlin: "kotlin";
            }>;
            implementationClassName: z.ZodString;
        }, z.core.$strip>>;
    }, z.core.$catchall<z.ZodObject<{
        language: z.ZodEnum<{
            "c++": "c++";
            swift: "swift";
            kotlin: "kotlin";
        }>;
        implementationClassName: z.ZodString;
    }, z.core.$strip>>>, z.ZodPipe<z.ZodObject<{
        cpp: z.ZodOptional<z.ZodString>;
        swift: z.ZodOptional<z.ZodString>;
        kotlin: z.ZodOptional<z.ZodString>;
    }, z.core.$strict>, z.ZodTransform<{
        [x: string]: {
            language: "c++" | "swift" | "kotlin";
            implementationClassName: string;
        };
        all?: {
            language: "c++";
            implementationClassName: string;
        } | undefined;
        ios?: {
            language: "c++" | "swift";
            implementationClassName: string;
        } | undefined;
        android?: {
            language: "c++" | "kotlin";
            implementationClassName: string;
        } | undefined;
    }, {
        cpp?: string | undefined;
        swift?: string | undefined;
        kotlin?: string | undefined;
    }>>]>>;
    ignorePaths: z.ZodOptional<z.ZodArray<z.ZodString>>;
    gitAttributesGeneratedFlag: z.ZodDefault<z.ZodOptional<z.ZodBoolean>>;
}, z.core.$strip>;
/**
 * Represents the structure of a `nitro.json` config file.
 */
export type NitroUserConfig = z.infer<typeof NitroUserConfigSchema>;
export {};
