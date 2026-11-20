import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import type { Device, Me, PairingCode, Role, User } from "./types";

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
