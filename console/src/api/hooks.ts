import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch, apiUpload } from "./client";
import type { Device, DocPages, DocStatus, Job, JobStatus, ManualDoc, Me, PairingCode, Role, Task, Technician, User } from "./types";

export const useMe = () => useQuery({ queryKey: ["me"], queryFn: () => apiFetch<Me>("/me") });

export const useUsers = (enabled: boolean) =>
  useQuery({ queryKey: ["users"], queryFn: () => apiFetch<{ users: User[] }>("/users"), enabled });

export const useDevices = (enabled: boolean) =>
  useQuery({ queryKey: ["devices"], queryFn: () => apiFetch<{ devices: Device[] }>("/devices"), enabled });

export function useCreateUser() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: { email: string; name: string; role: Role }) =>
      apiFetch<{ user: User }>("/users", { method: "POST", body: input }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["users"] }),
  });
}

export const useIssuePairingCode = () =>
  useMutation({
    mutationFn: (userId: string) => apiFetch<PairingCode>(`/users/${userId}/pairing-code`, { method: "POST" }),
  });

export function useRevokeDevice() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (deviceId: string) => apiFetch<{ device: Device }>(`/devices/${deviceId}/revoke`, { method: "POST" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["devices"] }),
  });
}

export const useJobs = (enabled: boolean) =>
  useQuery({ queryKey: ["jobs"], queryFn: () => apiFetch<{ jobs: Job[]; tasks: Task[] }>("/jobs"), enabled });

export const useTeam = (enabled: boolean) =>
  useQuery({ queryKey: ["team"], queryFn: () => apiFetch<{ technicians: Technician[] }>("/team"), enabled });

export const usePlaneIntegration = (enabled: boolean) =>
  useQuery({
    queryKey: ["plane"],
    queryFn: () => apiFetch<{ enabled: boolean; workspaceSlug: string | null; webUrl: string | null }>("/integrations/plane"),
    enabled,
    retry: false,
  });

export function useCreateJob() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: { title: string; site: string; description: string; assignedUserId: string; dueAt: string | null }) =>
      apiFetch<{ job: Job }>("/jobs", { method: "POST", body: input }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["jobs"] }),
  });
}

export function useUpdateJob() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string; revision: number; status: JobStatus }) =>
      apiFetch<{ job: Job }>(`/jobs/${id}`, { method: "PATCH", body }),
    onSettled: () => qc.invalidateQueries({ queryKey: ["jobs"] }),
  });
}

export function useAddTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ jobId, title }: { jobId: string; title: string }) =>
      apiFetch<{ id: string }>(`/jobs/${jobId}/tasks`, { method: "POST", body: { title } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["jobs"] }),
  });
}

export function useSetTaskDone() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string; revision: number; completed: boolean }) =>
      apiFetch<{ ok: true }>(`/tasks/${id}`, { method: "PATCH", body }),
    onSettled: () => qc.invalidateQueries({ queryKey: ["jobs"] }),
  });
}

export const useDocuments = (enabled: boolean) =>
  useQuery({ queryKey: ["documents"], queryFn: () => apiFetch<{ documents: ManualDoc[] }>("/documents"), enabled });

/** One document's extracted page text, read only when the reviewer opens it. */
export const useDocumentPages = (id: string, version: number) =>
  useQuery({
    queryKey: ["documents", id, "pages", version],
    queryFn: () => apiFetch<DocPages>(`/documents/${encodeURIComponent(id)}/pages`),
  });

/** Same-origin admin URL for the original uploaded file. */
export const documentFileUrl = (id: string) => `/api/admin/documents/${encodeURIComponent(id)}/file`;

export function useUploadDocument() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (form: FormData) => apiUpload<{ id: string; status: DocStatus }>("/documents", form),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["documents"] }),
  });
}

export function useReviewDocument() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, status, version }: { id: string; status: DocStatus; version: number }) =>
      apiFetch<{ ok: true }>(`/documents/${id}`, { method: "PATCH", body: { status, version } }),
    onSettled: () => qc.invalidateQueries({ queryKey: ["documents"] }),
  });
}
