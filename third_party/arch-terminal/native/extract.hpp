#pragma once
#include <string>
#include <atomic>
#include <cstddef>
namespace archterminal {
void extract(const std::string& archive, const std::string& destination, const std::atomic_bool* cancelled = nullptr);
size_t repair_directory_modes(const std::string& destination);
}
