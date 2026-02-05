import { z } from "zod";

const allowFromEntry = z.union([z.string(), z.number()]);

const wechatUiAccountSchema = z.object({
  name: z.string().optional(),
  enabled: z.boolean().optional(),

  bridgeUrl: z.string().optional(),
  bridgeToken: z.string().optional(),

  webhookPath: z.string().optional(),
  webhookSecret: z.string().optional(),

  dmPolicy: z.enum(["allowlist", "open", "disabled"]).optional(),
  allowFrom: z.array(allowFromEntry).optional(),

  textChunkLimit: z.number().int().positive().optional(),
});

export const WeChatUiConfigSchema = wechatUiAccountSchema.extend({
  accounts: z.object({}).catchall(wechatUiAccountSchema).optional(),
});

export type WeChatUiAccountConfig = z.infer<typeof wechatUiAccountSchema>;
export type WeChatUiConfig = z.infer<typeof WeChatUiConfigSchema>;

