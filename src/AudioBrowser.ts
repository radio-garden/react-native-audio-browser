// `./native` is deliberately NOT re-exported. It holds the raw Nitro hybrid
// object, whose `on*` properties are single callback slots that the emitters in
// `./features` own — a consumer assigning one silently unsubscribes every hook
// in the library. Feature modules import it directly; nothing else should.
export * from './features'
export * from './types'
export * from './utils/useDebug'
