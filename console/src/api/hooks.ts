import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch, apiUpload } from "./client";
import type { Device, DocPages, DocStatus, ManualDoc, Me, PairingCode, Role, User } from "./types";

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

export function useSetSenior() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ user, senior }: { user: User; senior: boolean }) =>
      apiFetch<{ user: User }>(`/users/${encodeURIComponent(user.id)}`, { method: "PATCH", body: { senior, updatedAt: user.updatedAt } }),
    onSettled: () => qc.invalidateQueries({ queryKey: ["users"] }),
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
