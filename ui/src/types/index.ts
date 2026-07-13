export type MessageType = "user" | "assistant" | "system" | "tool";

export interface KnowledgeBase {
  knowledgeBaseId: string;
  name: string;
  description: string;
}

export interface ToolCall {
  id: string;
  type: string;
  name: string;
  arguments: string;
}

export interface ToolResponse {
  id: string;
  name: string;
  responseData: string;
}

/**
 * RAG 检索命中的 chunk，附带引用溯源所需元数据。
 * 后端 `RetrievedChunk` 的镜像；`content` 已由后端截断到 200 字符预览。
 */
export interface RetrievedChunk {
  id?: string;
  documentId?: string;
  filename?: string;
  pageNumber?: number;
  headingPath?: string;
  chunkIndex?: number;
  content: string;
  score?: number;
}

export interface ChatMessageVOMetadata {
  toolCalls?: ToolCall[];
  toolResponse?: ToolResponse;
  /** 本轮 AI 回答依据的知识库片段（引用溯源） */
  references?: RetrievedChunk[];
}

export interface ChatMessageVO {
  id: string;
  sessionId: string;
  role: MessageType;
  content: string;
  metadata?: ChatMessageVOMetadata;
}

export type SseMessageType =
  | "AI_GENERATED_CONTENT"
  | "AI_PLANNING"
  | "AI_THINKING"
  | "AI_EXECUTING"
  | "AI_DONE"
  | "AI_REFERENCES";

export interface SseMessagePayload {
  message: ChatMessageVO;
  statusText: string;
  done: boolean;
  /** AI_REFERENCES 事件专用：本轮引用的 chunk 列表 */
  references?: RetrievedChunk[];
}

export interface SseMessageMetadata {
  chatMessageId: string;
}

export interface SseMessage {
  type: SseMessageType;
  payload: SseMessagePayload;
  metadata: SseMessageMetadata;
}
