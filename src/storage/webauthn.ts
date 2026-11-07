// Passkey-Anmeldung (WebAuthn) gegen TESS, siehe docs/sync-protocol.md (Credential B).
// Die JSON-Formen folgen WebAuthn Level 3 (toJSON, base64url); für Browser ohne parse*FromJSON/toJSON gibt es einen
// kleinen Nachbau.

export function base64urlEncode(data: ArrayBuffer | Uint8Array): string {
  const bytes = data instanceof Uint8Array ? data : new Uint8Array(data)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '')
}

export function base64urlDecode(value: string): ArrayBuffer {
  const base64 = value.replaceAll('-', '+').replaceAll('_', '/') + '='.repeat((4 - (value.length % 4)) % 4)
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes.buffer
}

type JsonDescriptor = { id: string; type: string; transports?: string[] }
export interface CreationOptionsJSON {
  rp: { id?: string; name: string }
  user: { id: string; name: string; displayName: string }
  challenge: string
  pubKeyCredParams: Array<{ type: string; alg: number }>
  timeout?: number
  excludeCredentials?: JsonDescriptor[]
  authenticatorSelection?: Record<string, unknown>
  attestation?: string
  extensions?: Record<string, unknown>
}
export interface RequestOptionsJSON {
  challenge: string
  timeout?: number
  rpId?: string
  allowCredentials?: JsonDescriptor[]
  userVerification?: string
  extensions?: Record<string, unknown>
}

type PkcStatic = {
  parseCreationOptionsFromJSON?: (options: CreationOptionsJSON) => PublicKeyCredentialCreationOptions
  parseRequestOptionsFromJSON?: (options: RequestOptionsJSON) => PublicKeyCredentialRequestOptions
}
const pkc = (): PkcStatic | undefined => (globalThis as { PublicKeyCredential?: PkcStatic }).PublicKeyCredential

const descriptor = (item: JsonDescriptor): PublicKeyCredentialDescriptor =>
  ({ id: base64urlDecode(item.id), type: item.type as PublicKeyCredentialType, transports: item.transports as AuthenticatorTransport[] | undefined })

export function creationOptionsFromJSON(options: CreationOptionsJSON, native = pkc()): PublicKeyCredentialCreationOptions {
  if (native?.parseCreationOptionsFromJSON) return native.parseCreationOptionsFromJSON(options)
  return {
    ...options,
    challenge: base64urlDecode(options.challenge),
    user: { ...options.user, id: base64urlDecode(options.user.id) },
    pubKeyCredParams: options.pubKeyCredParams as PublicKeyCredentialParameters[],
    excludeCredentials: options.excludeCredentials?.map(descriptor),
    attestation: options.attestation as AttestationConveyancePreference | undefined,
  } as PublicKeyCredentialCreationOptions
}

export function requestOptionsFromJSON(options: RequestOptionsJSON, native = pkc()): PublicKeyCredentialRequestOptions {
  if (native?.parseRequestOptionsFromJSON) return native.parseRequestOptionsFromJSON(options)
  return {
    ...options,
    challenge: base64urlDecode(options.challenge),
    allowCredentials: options.allowCredentials?.map(descriptor),
    userVerification: options.userVerification as UserVerificationRequirement | undefined,
  } as PublicKeyCredentialRequestOptions
}

type CredentialLike = {
  id: string; rawId: ArrayBuffer; type: string; authenticatorAttachment?: string | null
  response: Record<string, unknown> & { clientDataJSON: ArrayBuffer }
  getClientExtensionResults?: () => Record<string, unknown>
  toJSON?: () => unknown
}

export function credentialToJSON(credential: CredentialLike): Record<string, unknown> {
  if (typeof credential.toJSON === 'function') return credential.toJSON() as Record<string, unknown>
  const response = credential.response
  const json: Record<string, unknown> = { clientDataJSON: base64urlEncode(response.clientDataJSON) }
  if (response.attestationObject instanceof ArrayBuffer) {
    json.attestationObject = base64urlEncode(response.attestationObject)
    const transports = typeof response.getTransports === 'function' ? (response.getTransports as () => string[])() : []
    json.transports = transports
  } else {
    json.authenticatorData = base64urlEncode(response.authenticatorData as ArrayBuffer)
    json.signature = base64urlEncode(response.signature as ArrayBuffer)
    const handle = response.userHandle as ArrayBuffer | null | undefined
    if (handle) json.userHandle = base64urlEncode(handle)
  }
  return {
    id: credential.id, rawId: base64urlEncode(credential.rawId), type: credential.type,
    authenticatorAttachment: credential.authenticatorAttachment ?? undefined,
    clientExtensionResults: credential.getClientExtensionResults?.() ?? {}, response: json,
  }
}

export const passkeysSupported = () => typeof navigator !== 'undefined' && Boolean(navigator.credentials) && Boolean(pkc())

export async function createPasskey(options: CreationOptionsJSON): Promise<Record<string, unknown>> {
  const credential = await navigator.credentials.create({ publicKey: creationOptionsFromJSON(options) })
  if (!credential) throw new Error('Passkey wurde nicht angelegt.')
  return credentialToJSON(credential as unknown as CredentialLike)
}

export async function usePasskey(options: RequestOptionsJSON): Promise<Record<string, unknown>> {
  const credential = await navigator.credentials.get({ publicKey: requestOptionsFromJSON(options), mediation: 'optional' })
  if (!credential) throw new Error('Anmeldung abgebrochen.')
  return credentialToJSON(credential as unknown as CredentialLike)
}
