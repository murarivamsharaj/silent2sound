import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ChangeEvent, DragEvent } from 'react'
import axios from 'axios'
import {
  UploadCloud,
  AudioWaveform,
  FileVideo2,
  AlertTriangle,
  X,
  Loader2,
  Check,
  Download,
  Play,
  MessageSquareText,
  VolumeX,
} from 'lucide-react'

const MAX_FILE_SIZE = 100 * 1024 * 1024 // 100 MB
const ALLOWED_EXTENSIONS = ['mp4', 'mov']

// Match host dynamically (whether user opens localhost or 127.0.0.1)
const BACKEND_HOST = typeof window !== 'undefined' && window.location.hostname === '127.0.0.1' 
  ? '127.0.0.1' 
  : 'localhost'
const BACKEND_ORIGIN = `http://${BACKEND_HOST}:8080`
const UPLOAD_URL = `${BACKEND_ORIGIN}/api/v1/videos/upload`
const STATUS_URL = `${BACKEND_ORIGIN}/api/v1/videos/status/`
const POLL_INTERVAL_MS = 1500

interface UploadResponse {
  jobId: string
  status: string
  message?: string
}

interface JobStatusResponse {
  jobId: string
  status: string
  sceneDescription: string | null
  errorMessage: string | null
  hasPeople?: boolean
  dialogueTranscript?: string | null
  progressPercentage: number
  downloadUrl: string | null
}

const PIPELINE_STEPS = (hasPeople: boolean) => [
  {
    label: 'Uploaded',
    backendStatuses: ['PENDING', 'ANALYZING', 'GENERATING_AUDIO', 'MERGING', 'COMPLETED'],
  },
  {
    label: 'Visual Scene Extraction',
    backendStatuses: ['ANALYZING', 'GENERATING_AUDIO', 'MERGING', 'COMPLETED'],
  },
  {
    label: hasPeople ? 'Foley, Voice Synthesis & Lip-Sync' : 'Ambient Foley Generation',
    backendStatuses: ['GENERATING_AUDIO', 'MERGING', 'COMPLETED'],
  },
  {
    label: 'FFmpeg Audio Multiplexing',
    backendStatuses: ['MERGING', 'COMPLETED'],
  },
]

type Phase = 'idle' | 'submitting' | 'processing' | 'completed' | 'failed'

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB']
  let value = bytes
  let unit = 'B'
  for (const u of units) {
    if (value < 1024) break
    value /= 1024
    unit = u
  }
  return `${value.toFixed(1)} ${unit}`
}

function App() {
  const [file, setFile] = useState<File | null>(null)
  const [videoPreviewUrl, setVideoPreviewUrl] = useState<string | null>(null)
  const [prompt, setPrompt] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [phase, setPhase] = useState<Phase>('idle')
  const [jobId, setJobId] = useState<string | null>(null)
  const [jobStatus, setJobStatus] = useState<string | null>(null)
  const [progress, setProgress] = useState(0)
  const [downloadUrl, setDownloadUrl] = useState<string | null>(null)
  const [sceneDescription, setSceneDescription] = useState<string | null>(null)
  const [hasPeople, setHasPeople] = useState<boolean>(false)
  const [dialogueTranscript, setDialogueTranscript] = useState<string | null>(null)

  const inputRef = useRef<HTMLInputElement>(null)
  const [isDragOver, setIsDragOver] = useState(false)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const inFlightRef = useRef(false)
  const errorStreakRef = useRef(0)

  // Revoke previous object URL on change/unmount
  useEffect(() => {
    return () => {
      if (videoPreviewUrl) URL.revokeObjectURL(videoPreviewUrl)
    }
  }, [videoPreviewUrl])

  // Clear poller on unmount
  useEffect(() => {
    return () => {
      if (pollRef.current) clearInterval(pollRef.current)
    }
  }, [])

  const stopPolling = () => {
    if (pollRef.current) {
      clearInterval(pollRef.current)
      pollRef.current = null
    }
    inFlightRef.current = false
    errorStreakRef.current = 0
  }

  const startPolling = (id: string) => {
    stopPolling()

    const fetchStatus = async () => {
      if (inFlightRef.current) return
      inFlightRef.current = true
      try {
        const res = await axios.get<JobStatusResponse>(`${STATUS_URL}${id}`, {
          timeout: 10_000,
        })
        errorStreakRef.current = 0
        const data = res.data
        setJobStatus(data.status)
        setProgress((prev) => Math.max(prev, data.progressPercentage))
        if (data.sceneDescription) setSceneDescription(data.sceneDescription)
        setHasPeople(data.hasPeople ?? false)
        setDialogueTranscript(data.dialogueTranscript ?? null)

        if (data.status === 'COMPLETED') {
          setDownloadUrl(data.downloadUrl)
          setPhase('completed')
          stopPolling()
        } else if (data.status === 'FAILED') {
          setError(data.errorMessage || 'The processing pipeline reported a failure.')
          setPhase('failed')
          stopPolling()
        }
      } catch {
        errorStreakRef.current += 1
        if (errorStreakRef.current >= 4) {
          stopPolling()
          setError('Lost contact with the status service. Please try again later.')
          setPhase('failed')
        }
      } finally {
        inFlightRef.current = false
      }
    }

    void fetchStatus()
    pollRef.current = setInterval(fetchStatus, POLL_INTERVAL_MS)
  }

  const validateFile = useCallback((candidate: File): string | null => {
    const ext = candidate.name.split('.').pop()?.toLowerCase() ?? ''
    if (!ALLOWED_EXTENSIONS.includes(ext)) {
      return `Unsupported file type ".${ext}". Please upload an .mp4 or .mov video.`
    }
    if (candidate.size > MAX_FILE_SIZE) {
      return `File is too large (${formatBytes(candidate.size)}). The maximum size is 100 MB.`
    }
    return null
  }, [])

  const attachFile = useCallback(
    (candidate: File) => {
      const problem = validateFile(candidate)
      if (problem) {
        setError(problem)
        return
      }
      setError(null)
      setJobId(null)
      setJobStatus(null)
      setProgress(0)
      setDownloadUrl(null)
      setSceneDescription(null)
      setHasPeople(false)
      setDialogueTranscript(null)
      setPhase('idle')
      if (videoPreviewUrl) URL.revokeObjectURL(videoPreviewUrl)
      setVideoPreviewUrl(URL.createObjectURL(candidate))
      setFile(candidate)
    },
    [validateFile, videoPreviewUrl],
  )

  const clearFile = () => {
    stopPolling()
    if (videoPreviewUrl) URL.revokeObjectURL(videoPreviewUrl)
    setVideoPreviewUrl(null)
    setFile(null)
    setJobId(null)
    setJobStatus(null)
    setProgress(0)
    setDownloadUrl(null)
    setSceneDescription(null)
    setHasPeople(false)
    setDialogueTranscript(null)
    setPhase('idle')
    setError(null)
  }

  const onInputChange = (e: ChangeEvent<HTMLInputElement>) => {
    const picked = e.target.files?.[0]
    if (picked) attachFile(picked)
    e.target.value = ''
  }

  const onDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault()
    setIsDragOver(false)
    const dropped = e.dataTransfer.files?.[0]
    if (dropped) attachFile(dropped)
  }

  const onDragOver = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault()
    setIsDragOver(true)
  }

  const onDragLeave = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault()
    setIsDragOver(false)
  }

  const handleSubmit = async () => {
    if (!file || phase === 'submitting' || phase === 'processing') return
    setPhase('submitting')
    setError(null)

    const formData = new FormData()
    formData.append('file', file)
    if (prompt.trim()) formData.append('prompt', prompt.trim())

    try {
      // NOTE: Do not set Content-Type header manually; Axios will automatically
      // set multipart/form-data with the correct boundary string.
      const res = await axios.post<UploadResponse>(UPLOAD_URL, formData, {
        timeout: 120_000,
      })

      if (res.status === 202 && res.data?.jobId) {
        setJobId(res.data.jobId)
        setJobStatus(res.data.status ?? 'PENDING')
        setPhase('processing')
        startPolling(res.data.jobId)
      } else {
        setError(`Unexpected response from the server (HTTP ${res.status}).`)
        setPhase('idle')
      }
    } catch (err: unknown) {
      if (axios.isAxiosError(err)) {
        const serverMessage =
          (err.response?.data as { message?: string } | undefined)?.message ?? err.message
        setError(`Upload failed: ${serverMessage}`)
      } else {
        setError('Upload failed due to an unexpected client error.')
      }
      setPhase('idle')
    }
  }

  const pipelineSteps = useMemo(() => PIPELINE_STEPS(hasPeople), [hasPeople])
  const activeStep = useMemo(() => {
    if (phase !== 'processing' && phase !== 'completed' && phase !== 'failed') return -1
    if (!jobStatus) return -1
    return pipelineSteps.findIndex((s) => s.backendStatuses.includes(jobStatus))
  }, [phase, jobStatus, pipelineSteps])

  const resetAll = () => {
    clearFile()
    setPrompt('')
  }

  const busy = phase === 'submitting' || phase === 'processing'

  return (
    <div className="min-h-screen bg-void px-4 py-10 sm:px-6 lg:px-8">
      <div className="mx-auto flex w-full max-w-3xl flex-col gap-10">
        {/* ---------- Hero header ---------- */}
        <header className="text-center">
          <div className="mb-4 inline-flex items-center gap-2 rounded-full border border-edge bg-panel px-4 py-1.5 text-xs font-medium tracking-widest text-neon uppercase">
            <AudioWaveform className="h-4 w-4" aria-hidden />
            AI Foley Engine
          </div>
          <h1 className="text-4xl font-bold tracking-tight text-white sm:text-5xl">
            Silent2Sound: AI Video Foley &amp; Audio Generator
          </h1>
          <p className="mx-auto mt-4 max-w-xl text-sm leading-relaxed text-fog sm:text-base">
            Upload a silent clip, describe the atmosphere you imagine, and let the pipeline
            synthesize a full foley and voice track for it.
          </p>
        </header>

        {/* ---------- Error banner ---------- */}
        {error && (
          <div
            role="alert"
            className="flex items-start gap-3 rounded-xl border border-danger/40 bg-danger/10 px-4 py-3 text-sm text-danger"
          >
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            <p className="flex-1">{error}</p>
            <button
              type="button"
              onClick={() => setError(null)}
              className="rounded p-0.5 text-danger/70 transition hover:bg-danger/20 hover:text-danger"
              aria-label="Dismiss error"
            >
              <X className="h-4 w-4" aria-hidden />
            </button>
          </div>
        )}

        {/* ---------- Upload zone + preview + prompt ---------- */}
        <section className="rounded-2xl border border-edge bg-panel p-6 shadow-xl shadow-black/40 sm:p-8">
          <div
            onDrop={onDrop}
            onDragOver={onDragOver}
            onDragLeave={onDragLeave}
            onClick={() => inputRef.current?.click()}
            role="button"
            tabIndex={0}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') inputRef.current?.click()
            }}
            className={`group flex cursor-pointer flex-col items-center justify-center rounded-xl border-2 border-dashed px-6 py-12 text-center transition-colors ${
              isDragOver
                ? 'border-neon bg-neon-soft'
                : 'border-edge bg-panel2 hover:border-neon/50 hover:bg-neon-soft/50'
            }`}
          >
            <UploadCloud
              className={`mb-4 h-12 w-12 transition-colors ${
                isDragOver ? 'text-neon' : 'text-fog group-hover:text-neon'
              }`}
              aria-hidden
            />
            <p className="text-base font-medium text-white">
              Drag &amp; drop your video here, or <span className="text-neon">browse</span>
            </p>
            <p className="mt-2 text-xs text-fog">
              Accepts .mp4 or .mov &middot; up to 100 MB
            </p>
            <input
              ref={inputRef}
              type="file"
              accept=".mp4,.mov,video/mp4,video/quicktime"
              className="hidden"
              onChange={onInputChange}
            />
          </div>

          {/* Muted preview + file meta */}
          {file && videoPreviewUrl && (
            <div className="mt-6 flex flex-col gap-4 rounded-xl border border-edge bg-panel2 p-4 sm:flex-row sm:items-center">
              <video
                key={videoPreviewUrl}
                src={videoPreviewUrl}
                controls
                muted
                playsInline
                className="h-44 w-full rounded-lg border border-edge bg-black object-contain sm:w-64"
              />
              <div className="flex flex-1 flex-col gap-1 overflow-hidden">
                <div className="flex items-center gap-2 text-sm font-medium text-white">
                  <FileVideo2 className="h-4 w-4 shrink-0 text-neon" aria-hidden />
                  <span className="truncate" title={file.name}>
                    {file.name}
                  </span>
                </div>
                <p className="text-xs text-fog">Size: {formatBytes(file.size)}</p>
                <p className="text-xs text-fog">
                  Preview is muted &mdash; the original audio is ignored by the pipeline.
                </p>
                <button
                  type="button"
                  onClick={clearFile}
                  disabled={busy}
                  className="mt-2 inline-flex w-fit items-center gap-1 rounded-md border border-edge px-3 py-1 text-xs text-mist transition hover:border-danger/50 hover:text-danger disabled:cursor-not-allowed disabled:opacity-40"
                >
                  <X className="h-3 w-3" aria-hidden />
                  Remove video
                </button>
              </div>
            </div>
          )}

          {/* Prompt */}
          <div className="mt-6">
            <label htmlFor="sound-direction" className="mb-2 block text-sm font-medium text-white">
              Sound Direction / Custom Prompt{' '}
              <span className="font-normal text-fog">(optional)</span>
            </label>
            <textarea
              id="sound-direction"
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              rows={3}
              maxLength={500}
              placeholder="e.g. Busy street, rainy evening, footsteps on puddles"
              className="w-full resize-none rounded-xl border border-edge bg-panel2 px-4 py-3 text-sm text-mist placeholder:text-fog/60 focus:border-neon focus:ring-1 focus:ring-neon focus:outline-none"
            />
            <p className="mt-1 text-right text-xs text-fog">{prompt.length}/500</p>
          </div>

          <button
            type="button"
            onClick={handleSubmit}
            disabled={!file || busy}
            className="mt-2 flex w-full items-center justify-center gap-2 rounded-xl bg-neon px-6 py-3.5 text-sm font-semibold text-black transition hover:bg-cyan-300 disabled:cursor-not-allowed disabled:opacity-40"
          >
            {phase === 'submitting' ? (
              <>
                <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                Uploading&hellip;
              </>
            ) : phase === 'processing' ? (
              <>
                <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                Processing&hellip;
              </>
            ) : (
              <>
                <AudioWaveform className="h-4 w-4" aria-hidden />
                Generate Audio Track
              </>
            )}
          </button>
        </section>

        {/* ---------- Job progress card ---------- */}
        {(phase === 'processing' || phase === 'completed' || phase === 'failed') && jobId && (
          <section className="rounded-2xl border border-edge bg-panel p-6 shadow-xl shadow-black/40 sm:p-8">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <h2 className="text-lg font-semibold text-white">
                  {phase === 'completed'
                    ? 'Job completed'
                    : phase === 'failed'
                      ? 'Job failed'
                      : 'Job in progress'}
                </h2>
                <p className="mt-1 text-xs text-fog">
                  Job ID: <code className="rounded bg-panel2 px-1.5 py-0.5 text-neon">{jobId}</code>
                </p>
              </div>
              <span
                className={`inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs font-semibold tracking-wide uppercase ${
                  phase === 'failed'
                    ? 'border-danger/40 bg-danger/10 text-danger'
                    : 'border-neon/40 bg-neon-soft text-neon'
                }`}
              >
                <span
                  className={`h-2 w-2 rounded-full bg-current ${
                    phase === 'processing' ? 's2s-pulse' : ''
                  }`}
                  aria-hidden
                />
                {jobStatus ?? 'PENDING'}
              </span>
            </div>

            {/* Loading bar */}
            <div className="mt-6 h-2.5 w-full overflow-hidden rounded-full bg-panel2">
              <div
                className="h-full rounded-full bg-gradient-to-r from-violet to-neon transition-[width] duration-150 ease-linear"
                style={{ width: `${progress}%` }}
              />
            </div>
            <p className="mt-2 text-right text-xs text-fog">{progress}%</p>

            {/* Scene analysis outcome */}
            {(sceneDescription || hasPeople || phase === 'completed') && (
              <div className="mt-6 rounded-xl border border-edge bg-panel2 p-4">
                {sceneDescription && (
                  <p className="text-xs leading-relaxed text-fog">
                    <span className="font-semibold text-mist">Scene: </span>
                    {sceneDescription}
                  </p>
                )}
                {hasPeople && dialogueTranscript ? (
                  <div className="mt-3 rounded-2xl rounded-bl-sm border border-violet/40 bg-violet/10 px-4 py-3">
                    <p className="flex items-center gap-1.5 text-xs font-semibold tracking-wide text-violet uppercase">
                      <MessageSquareText className="h-3.5 w-3.5" aria-hidden />
                      Generated Speech Track
                    </p>
                    <p className="mt-1.5 text-sm text-mist italic">
                      &ldquo;{dialogueTranscript}&rdquo;
                    </p>
                  </div>
                ) : (
                  <span className="mt-3 inline-flex items-center gap-1.5 rounded-full border border-edge bg-panel px-3 py-1 text-xs font-medium text-fog">
                    <VolumeX className="h-3 w-3" aria-hidden />
                    Ambient &amp; Foley Only (No Speech)
                  </span>
                )}
              </div>
            )}

            {/* Step indicators */}
            <ol className="mt-6 space-y-4">
              {pipelineSteps.map((step, idx) => {
                const done = idx < activeStep || progress >= 100
                const current = idx === activeStep && progress < 100
                return (
                  <li key={step.label} className="flex items-start gap-3">
                    <span
                      className={`mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full border text-xs font-bold ${
                        done
                          ? 'border-neon bg-neon text-black'
                          : current
                            ? 'border-neon bg-neon-soft text-neon'
                            : 'border-edge bg-panel2 text-fog'
                      }`}
                    >
                      {done ? <Check className="h-3.5 w-3.5" aria-hidden /> : idx + 1}
                    </span>
                    <div>
                      <p
                        className={`text-sm font-medium ${
                          done || current ? 'text-white' : 'text-fog'
                        }`}
                      >
                        {idx + 1}. {step.label}
                      </p>
                      {current && (
                        <p className="s2s-pulse mt-0.5 text-xs text-neon">Working&hellip;</p>
                      )}
                    </div>
                  </li>
                )
              })}
            </ol>

            {/* Download / preview section */}
            {phase === 'completed' && downloadUrl && (
              <div className="mt-6 rounded-xl border border-neon/40 bg-neon-soft p-4">
                <h3 className="flex items-center gap-2 text-sm font-semibold text-white">
                  <Check className="h-4 w-4 text-neon" aria-hidden />
                  Download / Preview Synthesized Video
                </h3>
                <p className="mt-1 text-xs text-fog">
                  The multiplexed audio track has been merged into your video.
                </p>
                <div className="mt-3 flex flex-wrap gap-3">
                  <a
                    href={`${BACKEND_ORIGIN}${downloadUrl}`}
                    download
                    className="inline-flex items-center gap-2 rounded-lg bg-neon px-4 py-2 text-xs font-semibold text-black transition hover:bg-cyan-300"
                  >
                    <Download className="h-3.5 w-3.5" aria-hidden />
                    Download video
                  </a>
                  <a
                    href={`${BACKEND_ORIGIN}${downloadUrl}`}
                    target="_blank"
                    rel="noreferrer"
                    className="inline-flex items-center gap-2 rounded-lg border border-edge px-4 py-2 text-xs font-medium text-mist transition hover:border-neon/50 hover:text-neon"
                  >
                    <Play className="h-3.5 w-3.5" aria-hidden />
                    Preview in new tab
                  </a>
                </div>
              </div>
            )}

            <button
              type="button"
              onClick={resetAll}
              className="mt-6 w-full rounded-xl border border-edge px-6 py-3 text-sm font-medium text-mist transition hover:border-neon/50 hover:text-neon"
            >
              Start over
            </button>
          </section>
        )}
      </div>
    </div>
  )
}

export default App