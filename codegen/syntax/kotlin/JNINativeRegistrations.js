const jniNativeRegistrations = [];
export function addJNINativeRegistration(registerFunc) {
    jniNativeRegistrations.push(registerFunc);
}
export function getJNINativeRegistrations() {
    return jniNativeRegistrations;
}
