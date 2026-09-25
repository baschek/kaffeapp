import type { DriveSnapshot, InventoryDocument } from '../types'

declare global {
  interface Window {
    google?: {
      accounts: { oauth2: { initTokenClient(config: Record<string, unknown>): { requestAccessToken(options?: Record<string, unknown>): void } } }
    }
  }
}

const API = 'https://www.googleapis.com/drive/v3/files'
const UPLOAD = 'https://www.googleapis.com/upload/drive/v3/files'
const FOLDER_NAME = 'Kaffee-Bestand'
const BACKUP_FOLDER_NAME = 'Sicherungen'
const FILE_NAME = 'kaffee-bestand.json'

export class DriveConflictError extends Error {}

export class DriveStore {
  private token?: string
  private folderId?: string
  private backupFolderId?: string

  get connected() { return Boolean(this.token) }

  async connect(clientId: string): Promise<void> {
    if (!clientId) throw new Error('VITE_GOOGLE_CLIENT_ID ist noch nicht konfiguriert.')
    await loadGoogleLibrary()
    this.token = await new Promise<string>((resolve, reject) => {
      const client = window.google!.accounts.oauth2.initTokenClient({
        client_id: clientId,
        scope: 'https://www.googleapis.com/auth/drive.file',
        callback: (response: { access_token?: string; error?: string }) => response.access_token ? resolve(response.access_token) : reject(new Error(response.error || 'Google-Anmeldung abgebrochen.')),
        error_callback: () => reject(new Error('Google-Anmeldung fehlgeschlagen.')),
      })
      client.requestAccessToken({ prompt: '' })
    })
    await this.ensureFolders()
  }

  disconnect() { this.token = undefined }

  async load(): Promise<DriveSnapshot | null> {
    await this.ensureFolders()
    const files = await this.list(`name = '${FILE_NAME}' and '${this.folderId}' in parents and trashed = false`)
    if (!files.length) return null
    const meta = await this.metadata(files[0].id)
    const response = await this.request(`${API}/${files[0].id}?alt=media`)
    return { document: await response.json() as InventoryDocument, fileId: files[0].id, version: meta.version }
  }

  async save(document: InventoryDocument, expected?: DriveSnapshot | null, previous?: InventoryDocument): Promise<DriveSnapshot> {
    await this.ensureFolders()
    if (!expected) {
      const existing = await this.load()
      if (existing) throw new DriveConflictError('Die Drive-Datei wurde zwischenzeitlich angelegt.')
      const fileId = await this.uploadJson(FILE_NAME, document, this.folderId!)
      const meta = await this.metadata(fileId)
      return { document, fileId, version: meta.version }
    }
    const current = await this.metadata(expected.fileId)
    if (current.version !== expected.version) throw new DriveConflictError('Die Drive-Datei wurde auf einem anderen Gerät geändert.')
    if (previous) await this.createDailyBackup(previous)
    await this.request(`${UPLOAD}/${expected.fileId}?uploadType=media`, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json; charset=UTF-8' }, body: JSON.stringify(document, null, 2),
    })
    const meta = await this.metadata(expected.fileId)
    return { document, fileId: expected.fileId, version: meta.version }
  }

  async createDailyBackup(document: InventoryDocument, force = false): Promise<void> {
    await this.ensureFolders()
    const stamp = berlinDate(new Date())
    const name = force ? `kaffee-bestand-${stamp}-${new Date().toISOString().slice(11, 19).replaceAll(':', '')}.json` : `kaffee-bestand-${stamp}.json`
    if (!force) {
      const existing = await this.list(`name = '${name}' and '${this.backupFolderId}' in parents and trashed = false`)
      if (existing.length) return
    }
    await this.uploadJson(name, document, this.backupFolderId!)
  }

  private async ensureFolders() {
    if (this.folderId && this.backupFolderId) return
    this.folderId = await this.ensureFolder(FOLDER_NAME)
    this.backupFolderId = await this.ensureFolder(BACKUP_FOLDER_NAME, this.folderId)
  }

  private async ensureFolder(name: string, parent?: string) {
    const parentQuery = parent ? ` and '${parent}' in parents` : ''
    const found = await this.list(`name = '${name}' and mimeType = 'application/vnd.google-apps.folder'${parentQuery} and trashed = false`)
    if (found.length) return found[0].id
    const response = await this.request(API, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, mimeType: 'application/vnd.google-apps.folder', ...(parent ? { parents: [parent] } : {}) }),
    })
    return (await response.json() as { id: string }).id
  }

  private async uploadJson(name: string, document: InventoryDocument, parent: string) {
    const boundary = `coffee_${crypto.randomUUID()}`
    const body = `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify({ name, parents: [parent], mimeType: 'application/json' })}\r\n--${boundary}\r\nContent-Type: application/json\r\n\r\n${JSON.stringify(document, null, 2)}\r\n--${boundary}--`
    const response = await this.request(`${UPLOAD}?uploadType=multipart&fields=id`, {
      method: 'POST', headers: { 'Content-Type': `multipart/related; boundary=${boundary}` }, body,
    })
    return (await response.json() as { id: string }).id
  }

  private async metadata(fileId: string): Promise<{ id: string; version: string }> {
    const response = await this.request(`${API}/${fileId}?fields=id,version`)
    return response.json()
  }

  private async list(query: string): Promise<Array<{ id: string; name: string }>> {
    const params = new URLSearchParams({ q: query, fields: 'files(id,name)', spaces: 'drive' })
    const response = await this.request(`${API}?${params}`)
    return ((await response.json()) as { files: Array<{ id: string; name: string }> }).files || []
  }

  private async request(url: string, init: RequestInit = {}) {
    if (!this.token) throw new Error('Google Drive ist nicht verbunden.')
    const response = await fetch(url, { ...init, headers: { Authorization: `Bearer ${this.token}`, ...init.headers } })
    if (response.status === 401) { this.token = undefined; throw new Error('Google-Zugriff abgelaufen. Bitte erneut verbinden.') }
    if (!response.ok) throw new Error(`Google Drive: ${response.status} ${await response.text()}`)
    return response
  }
}

function loadGoogleLibrary(): Promise<void> {
  if (window.google?.accounts?.oauth2) return Promise.resolve()
  return new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>('script[data-google-identity]')
    if (existing) { existing.addEventListener('load', () => resolve()); existing.addEventListener('error', reject); return }
    const script = document.createElement('script')
    script.src = 'https://accounts.google.com/gsi/client'; script.async = true; script.defer = true; script.dataset.googleIdentity = 'true'
    script.onload = () => resolve(); script.onerror = () => reject(new Error('Google-Anmeldung konnte nicht geladen werden.'))
    document.head.appendChild(script)
  })
}

function berlinDate(date: Date) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Berlin', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(date)
  return `${parts.find(p => p.type === 'year')?.value}-${parts.find(p => p.type === 'month')?.value}-${parts.find(p => p.type === 'day')?.value}`
}
