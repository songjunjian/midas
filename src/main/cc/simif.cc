#include "simif.h"
#include <fstream>

simif_t::simif_t() {
  pass = true;
  t = 0;
  fail_t = 0;
  seed = time(NULL);
  srand(seed);
  trace_len = 128; // by master widget

#ifdef ENABLE_SNAPSHOT
  sample_file = std::string(TARGET_NAME) + ".sample";
  sample_num = 30; // SAMPLE_NUM;
  last_sample = NULL;
  last_sample_id = 0;
  profile = false;
  sample_count = 0;
  sample_time = 0;
  trace_count = 0;
#endif
}

void simif_t::load_mem(std::string filename) {
  std::ifstream file(filename.c_str());
  if (!file) {
    fprintf(stderr, "Cannot open %s\n", filename.c_str());
    exit(EXIT_FAILURE);
  }
  const size_t chunk = MEM_DATA_BITS / 4;
  size_t addr = 0;
  std::string line;
  while (std::getline(file, line)) {
    assert(line.length() % chunk == 0);
    for (int j = line.length() - chunk ; j >= 0 ; j -= chunk) {
      biguint_t data = 0;
      for (size_t k = 0 ; k < chunk ; k++) {
        data |= biguint_t(parse_nibble(line[j+k])) << (4*(chunk-1-k));
      }
      write_mem(addr, data);
      addr += chunk / 2;
    }
  }
  file.close();
}

void simif_t::init(int argc, char** argv, bool log, bool fast_loadmem) {
#ifdef ENABLE_SNAPSHOT
  // Read mapping files
  sample_t::init_chains(std::string(TARGET_NAME) + ".chain");
#endif

  for (size_t k = 0 ; k < 5 ; k++) {
    write(EMULATIONMASTER_HOST_RESET, 1);
    while(!read(EMULATIONMASTER_DONE));
    write(EMULATIONMASTER_SIM_RESET, 1);
    while(!read(EMULATIONMASTER_DONE));
    for (size_t i = 0 ; i < PEEK_SIZE ; i++) {
      peek_map[i] = read(i);
    }
#ifdef ENABLE_SNAPSHOT
    // flush traces from initialization
    trace_count = 1;
    read_traces(NULL);
    trace_count = 0;
#endif
  }

  this->log = log;
  std::vector<std::string> args(argv + 1, argv + argc);
  for (auto &arg: args) {
    if (!fast_loadmem && arg.find("+loadmem=") == 0) {
      std::string filename = arg.c_str()+9;
      fprintf(stdout, "[loadmem] start loading\n");
      load_mem(filename);
      fprintf(stdout, "[loadmem] done\n");
    }
    if (arg.find("+seed=") == 0) {
      seed = strtoll(arg.c_str()+6, NULL, 10);
    }
#ifdef ENABLE_SNAPSHOT
    if (arg.find("+sample=") == 0) {
      sample_file = arg.c_str()+8;
    }
    if (arg.find("+samplenum=") == 0) {
      sample_num = strtol(arg.c_str()+11, NULL, 10);
    }
    if (arg.find("+profile") == 0) profile = true;
#endif
  }

#ifdef ENABLE_SNAPSHOT
  samples = new sample_t*[sample_num];
  for (size_t i = 0 ; i < sample_num ; i++) samples[i] = NULL;
  if (profile) sim_start_time = timestamp();
#endif
}

int simif_t::finish() {
#ifdef ENABLE_SNAPSHOT
  // tail samples
  if (last_sample != NULL) {
    if (samples[last_sample_id] != NULL) delete samples[last_sample_id];
    samples[last_sample_id] = read_traces(last_sample);
  }

  // dump samples
  // std::ofstream file(filename.c_str());
  FILE *file = fopen(sample_file.c_str(), "w");
  for (size_t i = 0 ; i < sample_num ; i++) {
    if (samples[i] != NULL) {
      // file << *samples[i];
      samples[i]->dump(file);
      delete samples[i];
    }
  }

  if (profile) {
    double sim_time = (double) (timestamp() - sim_start_time) / 1000000.0;
    fprintf(stderr, "Simulation Time: %.3f s, Sample Time: %.3f s, Sample Count: %zu\n",
                    sim_time, (double) sample_time / 1000000.0, sample_count);
  }
#endif

  fprintf(stderr, "Runs %" PRIu64 " cycles\n", cycles());
  fprintf(stderr, "[%s] %s Test", pass ? "PASS" : "FAIL", TARGET_NAME);
  if (!pass) { fprintf(stdout, " at cycle %" PRIu64, fail_t); }
  fprintf(stderr, "\nSEED: %ld\n", seed);

  return pass ? EXIT_SUCCESS : EXIT_FAILURE;
}

void simif_t::step(size_t n) {
#ifdef ENABLE_SNAPSHOT
  // reservoir sampling
  if (t % trace_len == 0) {
    uint64_t start_time = 0;
    size_t record_id = t / trace_len;
    size_t sample_id = record_id < sample_num ? record_id : rand() % (record_id + 1);
    if (sample_id < sample_num) {
      sample_count++;
      if (profile) start_time = timestamp();
      if (last_sample != NULL) {
        if (samples[last_sample_id] != NULL) delete samples[last_sample_id];
        samples[last_sample_id] = read_traces(last_sample);
      }
      last_sample = read_snapshot();
      last_sample_id = sample_id;
      trace_count = 0;
      if (profile) sample_time += (timestamp() - start_time);
    }
  }
  if (trace_count < trace_len) trace_count += n;
#endif
  // take steps
  if (log) fprintf(stderr, "* STEP %zu -> %" PRIu64 " *\n", n, (t + n));
  write(EMULATIONMASTER_STEP, n);
  for (size_t i = 0 ; i < POKE_SIZE ; i++) {
    write(i, poke_map[i]);
  }
  while(!read(EMULATIONMASTER_DONE));
  for (size_t i = 0 ; i < PEEK_SIZE ; i++) {
    peek_map[i] = read(i);
  }
  t += n;
}

#ifdef ENABLE_SNAPSHOT
sample_t* simif_t::read_traces(sample_t *sample) {
  for (size_t i = 0 ; i < trace_count ; i++) {
    // input traces from FPGA
    for (idmap_it_t it = sample_t::in_tr_begin() ; it != sample_t::in_tr_end() ; it++) {
      size_t id = it->second;
      size_t chunk = sample_t::get_chunks(id);
      uint32_t *data = new uint32_t[chunk];
      for (size_t off = 0 ; off < chunk ; off++) {
        data[off] = read(id+off);
      }
      if (sample) sample->add_cmd(new poke_t(it->first, data, chunk));
      delete[] data;
    }
    if (sample) sample->add_cmd(new step_t(1));
    // output traces from FPGA
    for (idmap_it_t it = sample_t::out_tr_begin() ; it != sample_t::out_tr_end() ; it++) {
      size_t id = it->second;
      size_t chunk = sample_t::get_chunks(id);
      uint32_t *data = new uint32_t[chunk];
      for (size_t off = 0 ; off < chunk ; off++) {
        data[off] = read(id+off);
      }
      if (sample && i > 0) sample->add_cmd(new expect_t(it->first, data, chunk));
      delete[] data;
    }
  }

  return sample;
}

static inline char* int_to_bin(char *bin, uint32_t value, size_t size) {
  for (size_t i = 0 ; i < size; i++) {
    bin[i] = ((value >> (size-1-i)) & 0x1) + '0';
  }
  bin[size] = 0;
  return bin;
}

sample_t* simif_t::read_snapshot() {
  std::ostringstream snap;
  char bin[DAISY_WIDTH+1];
  for (size_t t = 0 ; t < CHAIN_NUM ; t++) {
    CHAIN_TYPE type = static_cast<CHAIN_TYPE>(t);
    const size_t chain_loop = sample_t::get_chain_loop(type);
    const size_t chain_len = sample_t::get_chain_len(type);
    for (size_t k = 0 ; k < chain_loop ; k++) {
      for (size_t i = 0 ; i < CHAIN_SIZE[t] ; i++) {
        if (type == SRAM_CHAIN) write(SRAM_RESTART_ADDR + i, 1);
        for (size_t j = 0 ; j < chain_len ; j++) {
          snap << int_to_bin(bin, read(CHAIN_ADDR[t] + i), DAISY_WIDTH);
        }
      }
    }
  }
  return new sample_t(snap.str().c_str(), cycles());
}
#endif