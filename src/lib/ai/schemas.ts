import { z } from 'zod';

export const ContextTagSchema = z.enum([
  'exercise_recent', 'exercise_planned', 'alcohol', 'illness', 'stress', 'poor_sleep', 'travel', 'other',
]);

export const FoodItemSchema = z.object({
  name: z.string(),
  carbsG: z.number(),
  fatG: z.number(),
  proteinG: z.number(),
  confidence: z.number(),
});

export const ExtractionResultSchema = z.object({
  intent: z.enum(['meal', 'correction_check', 'context_log', 'question']),
  meal: z
    .object({
      items: z.array(FoodItemSchema),
      totalCarbsG: z.number(),
      totalFatG: z.number(),
      totalProteinG: z.number(),
    })
    .nullable(),
  contextTags: z.array(ContextTagSchema).catch([]),
  userStatedBg: z.number().nullable(),
  userStatedDoseUnits: z.number().nullable(),
  question: z.string().nullable(),
  confidence: z.number(),
  clarificationNeeded: z.string().nullable(),
});

export const RationaleResultSchema = z.object({
  rationale: z.string(),
  cautions: z.array(z.string()).catch([]),
});

export const InsightSuggestionSchema = z.object({
  parameter: z.enum(['icr', 'isf', 'targetBg', 'behavior']),
  current: z.string(),
  suggested: z.string(),
  expectedEffect: z.string(),
  evidence: z.string(),
  confidence: z.number(),
});

export const InsightsResultSchema = z.object({
  suggestions: z.array(InsightSuggestionSchema),
});

export const TASK_SCHEMAS = {
  extract: ExtractionResultSchema,
  rationale: RationaleResultSchema,
  insights: InsightsResultSchema,
} as const;

export type AiTask = keyof typeof TASK_SCHEMAS;
