# Host build of the app's engine for the smoke test. Invoked by run.sh, which passes BUILD, CPP, HERE, JNI_INC, OPT, SAN.
OBJ := $(BUILD)/obj
include $(BUILD)/sources.mk

DEFS := -D_GNU_SOURCE -DGGML_USE_CPU -DGGML_BUILD -DGGML_CPU_GENERIC -DGGML_VERSION='"b4351"' -DGGML_COMMIT='"unknown"'
INC  := -I$(CPP) -I$(CPP)/ggml-cpu -I$(HERE)/shim -I$(JNI_INC) -I$(JNI_INC)/linux
CXXFLAGS := -std=c++17 $(OPT) -fno-finite-math-only -w $(DEFS) $(INC)
CFLAGS   := -std=gnu11 $(OPT) -fno-finite-math-only -w $(DEFS) $(INC)
# the JNI layer and the driver are OUR code, so they get warnings (and optional sanitizers); the engine is upstream
OURFLAGS := -std=c++17 $(OPT) -fno-finite-math-only $(DEFS) $(INC) -Wall -Wextra -Wno-unused-parameter $(SAN)

ENGINE_OBJS := $(patsubst %.c,$(OBJ)/%.c.o,$(C_SRCS)) $(patsubst %.cpp,$(OBJ)/%.cpp.o,$(CXX_SRCS))

$(BUILD)/smoke_test: $(ENGINE_OBJS) $(OBJ)/llama-jni.o $(OBJ)/smoke_test.o
	g++ $(SAN) -o $@ $^ -ldl -lpthread

$(OBJ)/%.c.o: $(CPP)/%.c
	@mkdir -p $(dir $@); echo "CC  $<"; gcc $(CFLAGS) -c $< -o $@
$(OBJ)/%.cpp.o: $(CPP)/%.cpp
	@mkdir -p $(dir $@); echo "CXX $<"; g++ $(CXXFLAGS) -c $< -o $@
$(OBJ)/llama-jni.o: $(CPP)/llama-jni.cpp $(CPP)/load_profile.h
	@mkdir -p $(dir $@); echo "CXX $<"; g++ $(OURFLAGS) -c $< -o $@
$(OBJ)/smoke_test.o: $(HERE)/smoke_test.cpp
	@mkdir -p $(dir $@); echo "CXX $<"; g++ $(OURFLAGS) -c $< -o $@
