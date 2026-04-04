# D&D Module Generator — Local LLM Project Guide

## Overview

Build a locally-running fine-tuned 7B LLM that generates D&D modules, served via Ollama and integrated with a Java (Spring Boot) application.

---

## 1. Running a Local LLM Server

### Ollama (Recommended)

```bash
# Install
curl -fsSL https://ollama.com/install.sh | sh

# Run a 7B model (starts a server on localhost:11434)
ollama run llama3.1:8b

# API endpoint:
curl http://localhost:11434/api/generate -d '{
  "model": "llama3.1:8b",
  "prompt": "Hello!"
}'
```

Ollama exposes an OpenAI-compatible endpoint at `/v1/chat/completions`.

### Other Options

- **llama.cpp** — more control, OpenAI-compatible API at `http://localhost:8080`
- **vLLM** — best throughput, requires ~16 GB VRAM for 7B float16
- **text-generation-webui** — web UI + API

### Key Tips

- **Quantization:** Use GGUF Q4_K_M or Q5_K_M on consumer hardware (8–16 GB RAM/VRAM)
- **GPU vs CPU:** GPU is 10–50× faster. Ollama and llama.cpp handle CPU inference well.
- **Popular 7–8B models:** Llama 3.1 8B, Mistral 7B, Gemma 2 7B, Qwen 2.5 7B

---

## 2. Why Fine-Tuning (Not RAG) for This Use Case

D&D module creation is a **creative skill and structure** problem — not a data lookup problem. Fine-tuning teaches the model *how* to write modules, which is ideal.

### When Training is NOT Best

- Data changes frequently → use RAG or tool use
- Model needs exact values (prices, IDs, dates) → use RAG or tool use
- Small dataset (<1000 rows) → context stuffing (put it in the prompt)

### When Training IS Best (This Project)

- Teaching output format and structure
- Domain reasoning and creative patterns
- Changing the model's behavior/style/expertise

---

## 3. Preparing Training Data

### Approach: Component-Level Training (Recommended to Start)

Break modules into building blocks and create examples for each.

#### Component Types

- Adventure hooks and plot outlines
- Room/location descriptions (read-aloud text + DM notes)
- Encounter design (enemies, tactics, terrain, difficulty)
- NPC profiles (personality, motivation, secrets)
- Puzzles and traps
- Treasure and loot tables
- Story arcs and pacing
- Stat block formatting
- Boxed text (read-aloud passages)
- Full module structure and flow

#### Example Training Data Format (JSONL)

```json
{"messages": [
  {"role": "system", "content": "You are an expert D&D 5e module designer. Write content in the style of published adventure modules with vivid descriptions, clear DM guidance, and balanced encounters."},
  {"role": "user", "content": "Write a room description for: a library in a lich's tower where animated books attack intruders. Include read-aloud text and DM notes."},
  {"role": "assistant", "content": "<your module's actual text for that room>"}
]}
```

```json
{"messages": [
  {"role": "user", "content": "Design a combat encounter for 4 level-3 players in a narrow canyon. Medium difficulty."},
  {"role": "assistant", "content": "## Ambush at Rattlesnake Pass\n\n**Setup:** ...\n**Enemies:** ...\n**Tactics:** ...\n**Terrain:** ..."}
]}
```

**Target: 200–500 high-quality examples** covering all component types.

---

## 4. Fine-Tuning with QLoRA

QLoRA allows fine-tuning a 7B model on a **single 24 GB GPU** (RTX 3090/4090).

### Dependencies

```bash
pip install transformers datasets peft bitsandbytes trl
```

### Training Script

```python
from datasets import load_dataset
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
from peft import LoraConfig
from trl import SFTTrainer, SFTConfig

# Load your data
dataset = load_dataset("json", data_files="dnd_training_data.jsonl")

# QLoRA setup
bnb_config = BitsAndBytesConfig(
    load_in_4bit=True,
    bnb_4bit_quant_type="nf4",
    bnb_4bit_compute_dtype="float16",
)

model_name = "meta-llama/Llama-3.1-8B-Instruct"
model = AutoModelForCausalLM.from_pretrained(
    model_name, quantization_config=bnb_config, device_map="auto"
)
tokenizer = AutoTokenizer.from_pretrained(model_name)
tokenizer.pad_token = tokenizer.eos_token

lora_config = LoraConfig(
    r=32,               # higher rank for creative tasks
    lora_alpha=64,
    target_modules=["q_proj", "v_proj", "k_proj", "o_proj",
                    "gate_proj", "up_proj", "down_proj"],
    lora_dropout=0.05,
    task_type="CAUSAL_LM",
)

training_args = SFTConfig(
    output_dir="./dnd-module-writer",
    num_train_epochs=3,
    per_device_train_batch_size=2,
    gradient_accumulation_steps=4,
    learning_rate=2e-4,
    warmup_steps=50,
    logging_steps=10,
    save_strategy="epoch",
    max_seq_length=4096,
)

trainer = SFTTrainer(
    model=model,
    args=training_args,
    train_dataset=dataset["train"],
    peft_config=lora_config,
    tokenizer=tokenizer,
)

trainer.train()
trainer.save_model("./dnd-module-adapter")
```

### Alternative Training Tools

- **Unsloth** — 2× faster QLoRA, less memory, works on free Colab GPUs
- **Axolotl** — config-driven, no code needed (just a YAML file)

---

## 5. After Training — Merging and Serving

### Merge LoRA Adapter into Base Model

```python
from peft import PeftModel
from transformers import AutoModelForCausalLM

base = AutoModelForCausalLM.from_pretrained("meta-llama/Llama-3.1-8B-Instruct")
model = PeftModel.from_pretrained(base, "./dnd-module-adapter")
merged = model.merge_and_unload()
merged.save_pretrained("./my-merged-model")
```

### Convert to GGUF and Load into Ollama

```bash
# Convert to GGUF format (using llama.cpp tools)
# Then create an Ollama model:
ollama create dnd-writer -f Modelfile

# Run anytime:
ollama run dnd-writer
```

### Output Files

```
dnd-module-adapter/
    adapter_model.safetensors   (~200 MB)
    adapter_config.json

# After merging and converting:
dnd-writer-7b-Q4_K_M.gguf      (~4-5 GB single file)
```

---

## 6. Retraining Lifecycle

Fine-tuning is **not** a one-time thing, but close to it in practice.

### Retrain When

- You get more/better training data
- You want to improve weak areas
- A better base model comes out
- You change your goals (e.g., adding wilderness hexcrawls)

### Typical Lifecycle

1. **V1** — train on initial dataset, test, find gaps
2. **V2** — add more examples to fix gaps, retrain
3. **V3** — maybe one more round of refinement
4. **Done** — run locally for months/years

### Important: Training vs Running

- **Training (occasional):** Rent a cloud GPU for 1-2 hours (~$1-2/hr on RunPod or Colab)
- **Running (daily):** Local machine with Ollama, CPU or GPU, no cloud needed

A 7B quantized model runs on a laptop with 16 GB RAM (CPU inference).

---

## 7. Architecture — Java + Python Hybrid

### Overview

```
┌─────────────────────────────────────┐
│  Java Application (Spring Boot)      │
│  - REST API for users/DMs            │
│  - Session management                │
│  - RAG orchestration                 │
│  - Module template logic             │
│  - PDF/document generation           │
└──────────────┬───────────────────────┘
               │ HTTP calls
┌──────────────▼───────────────────────┐
│  Ollama (runs locally, zero code)    │
│  - Serves your fine-tuned model      │
│  - OpenAI-compatible REST API        │
└──────────────────────────────────────┘
```

### Python (minimal, training only)

- `prepare_data.py` — converts modules into training JSONL
- `train.py` — runs QLoRA fine-tune (~100-150 lines total)

### Java (main application, using LangChain4j)

```java
// Connect to local Ollama instance
ChatLanguageModel model = OllamaChatModel.builder()
    .baseUrl("http://localhost:11434")
    .modelName("dnd-module-writer")
    .build();

// Simple generation
String response = model.generate("Create a trap for a level 3 party in a sewer");

// RAG with SRD/monster data
EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
    .embeddingStore(store)
    .embeddingModel(embeddingModel)
    .maxResults(5)
    .build();

Assistant assistant = AiServices.builder(DndModuleWriter.class)
    .chatLanguageModel(model)
    .contentRetriever(retriever)
    .build();
```

### Where Java Skills Shine

- Module template engine (hooks, encounters, rooms, NPCs)
- Encounter balancing logic (CR math, difficulty curves)
- PDF generation (iText or Apache PDFBox)
- RAG pipeline (indexing SRD content, monster manuals)
- Editing workflow (regenerate sections, adjust tone/difficulty)
- Persistence (saving modules, versioning, user preferences)

---

## 8. Hybrid Approach — Fine-Tuning + RAG (Best Results)

```
Fine-tuned model knows:
  → Module structure and pacing
  → How to write vivid room descriptions
  → Encounter design patterns
  → The "voice" and style of good modules

RAG provides at query time:
  → Monster stat blocks (from SRD/your bestiary)
  → Magic item details
  → Spell references
  → Your existing module content as inspiration
```

---

## 9. Getting Started — Step by Step

1. Install Ollama, run `llama3.1:8b`, call it from Java with LangChain4j — get the loop working
2. Build your Java app around the base model to nail the module generation workflow
3. Prepare training data in Python (the small script)
4. Fine-tune with QLoRA on a rented GPU (RunPod ~$1/hr) or Google Colab
5. Load your fine-tuned model into Ollama and swap it in — Java app doesn't change

---

## 10. D&D-Specific Tips

- **System prompt matters:** Include guidance about edition (5e/5.5e), formatting conventions
- **Separate read-aloud text:** Train the model to distinguish boxed/read-aloud text from DM notes
- **Encounter balance:** Include CR calculations and party composition in training prompts
- **Copyright:** Be careful with published modules (WotC, Kobold Press). Your own homebrew is safest. SRD content is fair game.
- **Start with components**, get that working well, then try full module generation
