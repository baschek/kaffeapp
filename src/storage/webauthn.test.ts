import { describe, expect, it } from 'vitest'
import { base64urlDecode, base64urlEncode, creationOptionsFromJSON, credentialToJSON, requestOptionsFromJSON } from './webauthn'

const bytes = (value: ArrayBuffer | BufferSource) => Array.from(new Uint8Array(value as ArrayBuffer))

describe('base64url', () => {
  it('kodiert ohne Auffüllung und mit URL-Zeichen', () => {
    const data = new Uint8Array([0, 251, 255, 62, 63, 1])
    const encoded = base64urlEncode(data)
    expect(encoded).toBe('APv_Pj8B')
    expect(bytes(base64urlDecode(encoded))).toEqual(Array.from(data))
    for (let length = 0; length < 8; length++) {
      const sample = new Uint8Array(length).map((_, index) => index * 37)
      expect(bytes(base64urlDecode(base64urlEncode(sample)))).toEqual(Array.from(sample))
    }
  })
})

describe('Passkey-Optionen', () => {
  const creation = {
    rp: { id: 'baschek.github.io', name: 'Kaffee-Bestand' }, user: { id: 'dXNlcg', name: 'jonathan', displayName: 'Jonathan' },
    challenge: 'Y2hhbGxlbmdl', pubKeyCredParams: [{ type: 'public-key', alg: -7 }],
    excludeCredentials: [{ id: 'AQID', type: 'public-key', transports: ['internal'] }],
    authenticatorSelection: { residentKey: 'required', userVerification: 'required' }, attestation: 'none',
  }

  it('wandelt Erstellungs-Optionen ohne native Hilfe um', () => {
    const options = creationOptionsFromJSON(creation, {})
    expect(new TextDecoder().decode(options.challenge as ArrayBuffer)).toBe('challenge')
    expect(new TextDecoder().decode(options.user.id as ArrayBuffer)).toBe('user')
    expect(bytes(options.excludeCredentials![0].id)).toEqual([1, 2, 3])
    expect(options.rp.id).toBe('baschek.github.io')
    expect(options.authenticatorSelection).toEqual({ residentKey: 'required', userVerification: 'required' })
  })

  it('nutzt die native Umwandlung, wenn der Browser sie hat', () => {
    const marker = { challenge: new ArrayBuffer(1) } as PublicKeyCredentialRequestOptions
    expect(requestOptionsFromJSON({ challenge: 'AA' }, { parseRequestOptionsFromJSON: () => marker })).toBe(marker)
    const fallback = requestOptionsFromJSON({ challenge: 'AQ', allowCredentials: [], userVerification: 'required' }, {})
    expect(bytes(fallback.challenge)).toEqual([1])
    expect(fallback.userVerification).toBe('required')
  })

  it('serialisiert Antworten wie toJSON()', () => {
    const buffer = (...values: number[]) => new Uint8Array(values).buffer
    const registration = credentialToJSON({
      id: 'AQI', rawId: buffer(1, 2), type: 'public-key', authenticatorAttachment: 'platform',
      response: { clientDataJSON: buffer(3), attestationObject: buffer(4), getTransports: () => ['internal'] },
      getClientExtensionResults: () => ({}),
    })
    expect(registration).toEqual({ id: 'AQI', rawId: 'AQI', type: 'public-key', authenticatorAttachment: 'platform', clientExtensionResults: {},
      response: { clientDataJSON: 'Aw', attestationObject: 'BA', transports: ['internal'] } })
    const assertion = credentialToJSON({
      id: 'AQI', rawId: buffer(1, 2), type: 'public-key', authenticatorAttachment: null,
      response: { clientDataJSON: buffer(3), authenticatorData: buffer(5), signature: buffer(6), userHandle: buffer(7) },
    })
    expect(assertion.response).toEqual({ clientDataJSON: 'Aw', authenticatorData: 'BQ', signature: 'Bg', userHandle: 'Bw' })
    const native = { toJSON: () => ({ id: 'native' }) }
    expect(credentialToJSON(native as never)).toEqual({ id: 'native' })
  })
})
