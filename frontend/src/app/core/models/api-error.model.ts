export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
  requestId: string | null;
  fieldErrors: Record<string, string> | null;
}
