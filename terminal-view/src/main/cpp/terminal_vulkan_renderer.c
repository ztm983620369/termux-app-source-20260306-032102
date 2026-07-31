#define VK_USE_PLATFORM_ANDROID_KHR

#include <android/bitmap.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <vulkan/vulkan.h>

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

static const uint32_t terminal_vert_spv[] =
#include "terminal.vert.inc"
;
static const uint32_t terminal_frag_spv[] =
#include "terminal.frag.inc"
;

#define LOG_TAG "TermuxVulkanNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define TERMUX_FRAMES_IN_FLIGHT 2u
#define TERMUX_INITIAL_STAGING_BYTES (256u * 1024u)
#define TERMUX_MAX_STAGING_BYTES (16u * 1024u * 1024u)
#define TERMUX_INITIAL_VERTEX_BYTES (1024u * 1024u)
#define TERMUX_MAX_VERTEX_BYTES (16u * 1024u * 1024u)
#define TERMUX_INSTANCE_BYTES 32u
#define TERMUX_VERTICES_PER_INSTANCE 6u

typedef struct TermuxBuffer {
    VkBuffer buffer;
    VkDeviceMemory memory;
    VkDeviceSize size;
} TermuxBuffer;

typedef struct TermuxTexture {
    VkImage image;
    VkDeviceMemory memory;
    VkImageView view;
    VkFormat format;
    uint32_t width;
    uint32_t height;
    VkImageLayout layout;
} TermuxTexture;

typedef struct TermuxFrameResources {
    VkCommandBuffer commandBuffer;
    VkSemaphore imageAvailable;
    VkSemaphore renderFinished;
    VkFence fence;
    TermuxBuffer staging;
    TermuxBuffer vertex;
    uint64_t vertexGeneration;
    size_t vertexBytes;
    bool vertexValid;
} TermuxFrameResources;

typedef struct TermuxRenderer {
    ANativeWindow *window;
    VkInstance instance;
    VkPhysicalDevice physicalDevice;
    VkDevice device;
    VkQueue queue;
    uint32_t queueFamily;
    VkDeviceSize stagingAlignment;
    VkSurfaceKHR surface;

    VkSwapchainKHR swapchain;
    VkFormat swapchainFormat;
    VkExtent2D extent;
    uint32_t swapchainImageCount;
    VkImage *swapchainImages;
    VkImageView *swapchainViews;
    VkFramebuffer *framebuffers;

    VkRenderPass renderPass;
    VkDescriptorSetLayout descriptorSetLayout;
    VkDescriptorPool descriptorPool;
    VkDescriptorSet descriptorSet;
    VkSampler sampler;
    VkPipelineLayout pipelineLayout;
    VkPipeline pipeline;
    VkCommandPool commandPool;

    TermuxTexture maskTexture;
    TermuxTexture colorTexture;
    TermuxTexture runMaskTexture;
    TermuxFrameResources frames[TERMUX_FRAMES_IN_FLIGHT];
    uint32_t currentFrame;
    uint32_t requestedWidth;
    uint32_t requestedHeight;
    uint64_t renderedFrames;
    uint64_t uploadedRegions;
    uint64_t vertexUploads;
    uint64_t vertexUploadReuses;
    uint64_t swapchainRecreates;
    uint64_t retryCount;
    bool initialized;
} TermuxRenderer;

typedef struct TermuxUploadRegion {
    int left;
    int top;
    int right;
    int bottom;
    size_t bytesPerPixel;
    size_t rowBytes;
    size_t uploadBytes;
} TermuxUploadRegion;

static bool vk_succeeded(VkResult result, const char *operation) {
    if (result == VK_SUCCESS) return true;
    LOGE("%s failed VkResult=%d", operation, (int) result);
    return false;
}

static void destroy_buffer(TermuxRenderer *renderer, TermuxBuffer *buffer) {
    if (buffer->buffer != VK_NULL_HANDLE) {
        vkDestroyBuffer(renderer->device, buffer->buffer, NULL);
    }
    if (buffer->memory != VK_NULL_HANDLE) {
        vkFreeMemory(renderer->device, buffer->memory, NULL);
    }
    memset(buffer, 0, sizeof(*buffer));
}

static void destroy_texture(TermuxRenderer *renderer, TermuxTexture *texture) {
    if (texture->view != VK_NULL_HANDLE) {
        vkDestroyImageView(renderer->device, texture->view, NULL);
    }
    if (texture->image != VK_NULL_HANDLE) {
        vkDestroyImage(renderer->device, texture->image, NULL);
    }
    if (texture->memory != VK_NULL_HANDLE) {
        vkFreeMemory(renderer->device, texture->memory, NULL);
    }
    memset(texture, 0, sizeof(*texture));
}

static bool find_memory_type(TermuxRenderer *renderer, uint32_t typeBits,
                             VkMemoryPropertyFlags properties, uint32_t *index) {
    VkPhysicalDeviceMemoryProperties memoryProperties;
    vkGetPhysicalDeviceMemoryProperties(renderer->physicalDevice, &memoryProperties);
    for (uint32_t i = 0; i < memoryProperties.memoryTypeCount; ++i) {
        if ((typeBits & (1u << i)) != 0u &&
            (memoryProperties.memoryTypes[i].propertyFlags & properties) == properties) {
            *index = i;
            return true;
        }
    }
    LOGE("no compatible Vulkan memory type bits=0x%x props=0x%x", typeBits,
         (unsigned) properties);
    return false;
}

static bool allocate_memory(TermuxRenderer *renderer, const VkMemoryRequirements *requirements,
                            VkMemoryPropertyFlags properties, VkDeviceMemory *memory) {
    uint32_t typeIndex = 0;
    if (!find_memory_type(renderer, requirements->memoryTypeBits, properties, &typeIndex)) {
        return false;
    }
    VkMemoryAllocateInfo allocateInfo = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = requirements->size,
        .memoryTypeIndex = typeIndex,
    };
    return vk_succeeded(vkAllocateMemory(renderer->device, &allocateInfo, NULL, memory),
                        "vkAllocateMemory");
}

static bool create_buffer(TermuxRenderer *renderer, VkDeviceSize size,
                          VkBufferUsageFlags usage, VkMemoryPropertyFlags properties,
                          TermuxBuffer *buffer) {
    memset(buffer, 0, sizeof(*buffer));
    VkBufferCreateInfo bufferInfo = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = size,
        .usage = usage,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
    };
    if (!vk_succeeded(vkCreateBuffer(renderer->device, &bufferInfo, NULL, &buffer->buffer),
                      "vkCreateBuffer")) {
        return false;
    }
    VkMemoryRequirements requirements;
    vkGetBufferMemoryRequirements(renderer->device, buffer->buffer, &requirements);
    if (!allocate_memory(renderer, &requirements, properties, &buffer->memory) ||
        !vk_succeeded(vkBindBufferMemory(renderer->device, buffer->buffer, buffer->memory, 0),
                      "vkBindBufferMemory")) {
        destroy_buffer(renderer, buffer);
        return false;
    }
    buffer->size = size;
    return true;
}

static bool create_texture(TermuxRenderer *renderer, VkFormat format, uint32_t width,
                           uint32_t height, TermuxTexture *texture) {
    memset(texture, 0, sizeof(*texture));
    texture->format = format;
    texture->width = width;
    texture->height = height;
    texture->layout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkImageCreateInfo imageInfo = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = format,
        .extent = { width, height, 1 },
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    if (!vk_succeeded(vkCreateImage(renderer->device, &imageInfo, NULL, &texture->image),
                      "vkCreateImage")) {
        return false;
    }
    VkMemoryRequirements requirements;
    vkGetImageMemoryRequirements(renderer->device, texture->image, &requirements);
    if (!allocate_memory(renderer, &requirements, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                         &texture->memory) ||
        !vk_succeeded(vkBindImageMemory(renderer->device, texture->image, texture->memory, 0),
                      "vkBindImageMemory")) {
        destroy_texture(renderer, texture);
        return false;
    }
    VkImageViewCreateInfo viewInfo = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .image = texture->image,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = format,
        .subresourceRange = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .baseMipLevel = 0,
            .levelCount = 1,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
    };
    if (!vk_succeeded(vkCreateImageView(renderer->device, &viewInfo, NULL, &texture->view),
                      "vkCreateImageView")) {
        destroy_texture(renderer, texture);
        return false;
    }
    return true;
}

static bool create_shader_module(TermuxRenderer *renderer, const uint32_t *code,
                                 size_t wordCount, VkShaderModule *module) {
    VkShaderModuleCreateInfo info = {
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = wordCount * sizeof(uint32_t),
        .pCode = code,
    };
    return vk_succeeded(vkCreateShaderModule(renderer->device, &info, NULL, module),
                        "vkCreateShaderModule");
}

static bool has_device_extension(VkPhysicalDevice physicalDevice, const char *name) {
    uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(physicalDevice, NULL, &count, NULL) != VK_SUCCESS) {
        return false;
    }
    VkExtensionProperties *properties = calloc(count, sizeof(*properties));
    if (properties == NULL) return false;
    bool found = false;
    if (vkEnumerateDeviceExtensionProperties(physicalDevice, NULL, &count, properties) ==
        VK_SUCCESS) {
        for (uint32_t i = 0; i < count; ++i) {
            if (strcmp(properties[i].extensionName, name) == 0) {
                found = true;
                break;
            }
        }
    }
    free(properties);
    return found;
}

static void destroy_swapchain(TermuxRenderer *renderer) {
    if (renderer->device == VK_NULL_HANDLE) return;
    if (renderer->framebuffers != NULL) {
        for (uint32_t i = 0; i < renderer->swapchainImageCount; ++i) {
            if (renderer->framebuffers[i] != VK_NULL_HANDLE) {
                vkDestroyFramebuffer(renderer->device, renderer->framebuffers[i], NULL);
            }
        }
    }
    if (renderer->swapchainViews != NULL) {
        for (uint32_t i = 0; i < renderer->swapchainImageCount; ++i) {
            if (renderer->swapchainViews[i] != VK_NULL_HANDLE) {
                vkDestroyImageView(renderer->device, renderer->swapchainViews[i], NULL);
            }
        }
    }
    free(renderer->framebuffers);
    free(renderer->swapchainViews);
    free(renderer->swapchainImages);
    renderer->framebuffers = NULL;
    renderer->swapchainViews = NULL;
    renderer->swapchainImages = NULL;
    renderer->swapchainImageCount = 0;
    if (renderer->swapchain != VK_NULL_HANDLE) {
        vkDestroySwapchainKHR(renderer->device, renderer->swapchain, NULL);
        renderer->swapchain = VK_NULL_HANDLE;
    }
}

static VkSurfaceFormatKHR choose_surface_format(const VkSurfaceFormatKHR *formats,
                                                uint32_t count) {
    for (uint32_t i = 0; i < count; ++i) {
        if (formats[i].format == VK_FORMAT_R8G8B8A8_UNORM &&
            formats[i].colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
            return formats[i];
        }
    }
    for (uint32_t i = 0; i < count; ++i) {
        if (formats[i].format == VK_FORMAT_B8G8R8A8_UNORM) return formats[i];
    }
    return formats[0];
}

static VkCompositeAlphaFlagBitsKHR choose_composite_alpha(VkCompositeAlphaFlagsKHR supported) {
    const VkCompositeAlphaFlagBitsKHR candidates[] = {
        VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
        VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
        VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
        VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
    };
    for (size_t i = 0; i < sizeof(candidates) / sizeof(candidates[0]); ++i) {
        if ((supported & candidates[i]) != 0) return candidates[i];
    }
    return VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
}

static uint32_t clamp_u32(uint32_t value, uint32_t minValue, uint32_t maxValue) {
    if (value < minValue) return minValue;
    if (maxValue != 0 && value > maxValue) return maxValue;
    return value;
}

static bool create_render_pass(TermuxRenderer *renderer, VkFormat format) {
    VkAttachmentDescription attachment = {
        .format = format,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR,
        .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
        .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
        .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        .finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
    };
    VkAttachmentReference colorReference = {
        .attachment = 0,
        .layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
    };
    VkSubpassDescription subpass = {
        .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
        .colorAttachmentCount = 1,
        .pColorAttachments = &colorReference,
    };
    VkSubpassDependency dependency = {
        .srcSubpass = VK_SUBPASS_EXTERNAL,
        .dstSubpass = 0,
        .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
        .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
        .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
    };
    VkRenderPassCreateInfo info = {
        .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
        .attachmentCount = 1,
        .pAttachments = &attachment,
        .subpassCount = 1,
        .pSubpasses = &subpass,
        .dependencyCount = 1,
        .pDependencies = &dependency,
    };
    return vk_succeeded(vkCreateRenderPass(renderer->device, &info, NULL,
                                           &renderer->renderPass), "vkCreateRenderPass");
}

static bool create_descriptor_state(TermuxRenderer *renderer) {
    VkDescriptorSetLayoutBinding bindings[3] = {
        {
            .binding = 0,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
        },
        {
            .binding = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
        },
        {
            .binding = 2,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
        },
    };
    VkDescriptorSetLayoutCreateInfo layoutInfo = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = 3,
        .pBindings = bindings,
    };
    if (!vk_succeeded(vkCreateDescriptorSetLayout(renderer->device, &layoutInfo, NULL,
                                                   &renderer->descriptorSetLayout),
                      "vkCreateDescriptorSetLayout")) {
        return false;
    }
    VkDescriptorPoolSize poolSize = {
        .type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
        .descriptorCount = 3,
    };
    VkDescriptorPoolCreateInfo poolInfo = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .maxSets = 1,
        .poolSizeCount = 1,
        .pPoolSizes = &poolSize,
    };
    if (!vk_succeeded(vkCreateDescriptorPool(renderer->device, &poolInfo, NULL,
                                              &renderer->descriptorPool),
                      "vkCreateDescriptorPool")) {
        return false;
    }
    VkDescriptorSetAllocateInfo allocateInfo = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = renderer->descriptorPool,
        .descriptorSetCount = 1,
        .pSetLayouts = &renderer->descriptorSetLayout,
    };
    if (!vk_succeeded(vkAllocateDescriptorSets(renderer->device, &allocateInfo,
                                                &renderer->descriptorSet),
                      "vkAllocateDescriptorSets")) {
        return false;
    }
    VkSamplerCreateInfo samplerInfo = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .magFilter = VK_FILTER_LINEAR,
        .minFilter = VK_FILTER_LINEAR,
        .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
        .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .mipLodBias = 0.0f,
        .anisotropyEnable = VK_FALSE,
        .maxAnisotropy = 1.0f,
        .compareEnable = VK_FALSE,
        .compareOp = VK_COMPARE_OP_ALWAYS,
        .minLod = 0.0f,
        .maxLod = 0.0f,
        .borderColor = VK_BORDER_COLOR_INT_TRANSPARENT_BLACK,
        .unnormalizedCoordinates = VK_FALSE,
    };
    return vk_succeeded(vkCreateSampler(renderer->device, &samplerInfo, NULL,
                                         &renderer->sampler), "vkCreateSampler");
}

static bool update_descriptor_state(TermuxRenderer *renderer) {
    if (renderer->descriptorSet == VK_NULL_HANDLE || renderer->sampler == VK_NULL_HANDLE ||
        renderer->maskTexture.view == VK_NULL_HANDLE ||
        renderer->colorTexture.view == VK_NULL_HANDLE ||
        renderer->runMaskTexture.view == VK_NULL_HANDLE) {
        return false;
    }
    VkDescriptorImageInfo mask = {
        .sampler = renderer->sampler,
        .imageView = renderer->maskTexture.view,
        .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
    };
    VkDescriptorImageInfo color = {
        .sampler = renderer->sampler,
        .imageView = renderer->colorTexture.view,
        .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
    };
    VkDescriptorImageInfo runMask = {
        .sampler = renderer->sampler,
        .imageView = renderer->runMaskTexture.view,
        .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
    };
    VkWriteDescriptorSet writes[3] = {
        {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = renderer->descriptorSet,
            .dstBinding = 0,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .pImageInfo = &mask,
        },
        {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = renderer->descriptorSet,
            .dstBinding = 1,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .pImageInfo = &color,
        },
        {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = renderer->descriptorSet,
            .dstBinding = 2,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .pImageInfo = &runMask,
        },
    };
    vkUpdateDescriptorSets(renderer->device, 3, writes, 0, NULL);
    return true;
}

static bool create_pipeline(TermuxRenderer *renderer) {
    VkShaderModule vertexModule = VK_NULL_HANDLE;
    VkShaderModule fragmentModule = VK_NULL_HANDLE;
    if (!create_shader_module(renderer, terminal_vert_spv,
                               sizeof(terminal_vert_spv) / sizeof(uint32_t), &vertexModule) ||
        !create_shader_module(renderer, terminal_frag_spv,
                              sizeof(terminal_frag_spv) / sizeof(uint32_t), &fragmentModule)) {
        if (vertexModule != VK_NULL_HANDLE) vkDestroyShaderModule(renderer->device, vertexModule, NULL);
        if (fragmentModule != VK_NULL_HANDLE) vkDestroyShaderModule(renderer->device, fragmentModule, NULL);
        return false;
    }
    VkPipelineShaderStageCreateInfo stages[2] = {
        {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .stage = VK_SHADER_STAGE_VERTEX_BIT,
            .module = vertexModule,
            .pName = "main",
        },
        {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .stage = VK_SHADER_STAGE_FRAGMENT_BIT,
            .module = fragmentModule,
            .pName = "main",
        },
    };
    VkVertexInputBindingDescription binding = {
        .binding = 0,
        .stride = TERMUX_INSTANCE_BYTES,
        .inputRate = VK_VERTEX_INPUT_RATE_INSTANCE,
    };
    VkVertexInputAttributeDescription attributes[4] = {
        { .location = 0, .binding = 0, .format = VK_FORMAT_R32G32B32A32_SFLOAT, .offset = 0 },
        { .location = 1, .binding = 0, .format = VK_FORMAT_R16G16B16A16_UINT, .offset = 16 },
        { .location = 2, .binding = 0, .format = VK_FORMAT_R8G8B8A8_UNORM, .offset = 24 },
        { .location = 3, .binding = 0, .format = VK_FORMAT_R32_UINT, .offset = 28 },
    };
    VkPipelineVertexInputStateCreateInfo vertexInput = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO,
        .vertexBindingDescriptionCount = 1,
        .pVertexBindingDescriptions = &binding,
        .vertexAttributeDescriptionCount = 4,
        .pVertexAttributeDescriptions = attributes,
    };
    VkPipelineInputAssemblyStateCreateInfo inputAssembly = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
        .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
        .primitiveRestartEnable = VK_FALSE,
    };
    VkPipelineViewportStateCreateInfo viewport = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
        .viewportCount = 1,
        .scissorCount = 1,
    };
    VkPipelineRasterizationStateCreateInfo rasterization = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
        .depthClampEnable = VK_FALSE,
        .rasterizerDiscardEnable = VK_FALSE,
        .polygonMode = VK_POLYGON_MODE_FILL,
        .cullMode = VK_CULL_MODE_NONE,
        .frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE,
        .depthBiasEnable = VK_FALSE,
        .lineWidth = 1.0f,
    };
    VkPipelineMultisampleStateCreateInfo multisample = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
        .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT,
    };
    VkPipelineColorBlendAttachmentState blendAttachment = {
        .blendEnable = VK_TRUE,
        .srcColorBlendFactor = VK_BLEND_FACTOR_ONE,
        .dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
        .colorBlendOp = VK_BLEND_OP_ADD,
        .srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE,
        .dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
        .alphaBlendOp = VK_BLEND_OP_ADD,
        .colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
            VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT,
    };
    VkPipelineColorBlendStateCreateInfo blend = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
        .logicOpEnable = VK_FALSE,
        .attachmentCount = 1,
        .pAttachments = &blendAttachment,
    };
    VkDynamicState dynamicStates[2] = { VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR };
    VkPipelineDynamicStateCreateInfo dynamic = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
        .dynamicStateCount = 2,
        .pDynamicStates = dynamicStates,
    };
    VkPushConstantRange pushConstant = {
        .stageFlags = VK_SHADER_STAGE_VERTEX_BIT,
        .offset = 0,
        .size = sizeof(float) * 8,
    };
    VkPipelineLayoutCreateInfo layoutInfo = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1,
        .pSetLayouts = &renderer->descriptorSetLayout,
        .pushConstantRangeCount = 1,
        .pPushConstantRanges = &pushConstant,
    };
    if (!vk_succeeded(vkCreatePipelineLayout(renderer->device, &layoutInfo, NULL,
                                              &renderer->pipelineLayout),
                      "vkCreatePipelineLayout")) {
        vkDestroyShaderModule(renderer->device, vertexModule, NULL);
        vkDestroyShaderModule(renderer->device, fragmentModule, NULL);
        return false;
    }
    VkGraphicsPipelineCreateInfo pipelineInfo = {
        .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
        .stageCount = 2,
        .pStages = stages,
        .pVertexInputState = &vertexInput,
        .pInputAssemblyState = &inputAssembly,
        .pViewportState = &viewport,
        .pRasterizationState = &rasterization,
        .pMultisampleState = &multisample,
        .pColorBlendState = &blend,
        .pDynamicState = &dynamic,
        .layout = renderer->pipelineLayout,
        .renderPass = renderer->renderPass,
        .subpass = 0,
    };
    bool result = vk_succeeded(vkCreateGraphicsPipelines(renderer->device, VK_NULL_HANDLE, 1,
                                                          &pipelineInfo, NULL,
                                                          &renderer->pipeline),
                                "vkCreateGraphicsPipelines");
    vkDestroyShaderModule(renderer->device, vertexModule, NULL);
    vkDestroyShaderModule(renderer->device, fragmentModule, NULL);
    return result;
}

static bool create_swapchain(TermuxRenderer *renderer, uint32_t requestedWidth,
                             uint32_t requestedHeight) {
    VkSurfaceCapabilitiesKHR capabilities;
    if (!vk_succeeded(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(renderer->physicalDevice,
                                                                 renderer->surface,
                                                                 &capabilities),
                      "vkGetPhysicalDeviceSurfaceCapabilitiesKHR")) {
        return false;
    }
    uint32_t formatCount = 0;
    if (!vk_succeeded(vkGetPhysicalDeviceSurfaceFormatsKHR(renderer->physicalDevice,
                                                            renderer->surface, &formatCount, NULL),
                      "vkGetPhysicalDeviceSurfaceFormatsKHR(count)")) {
        return false;
    }
    if (formatCount == 0) return false;
    VkSurfaceFormatKHR *formats = calloc(formatCount, sizeof(*formats));
    if (formats == NULL) return false;
    bool formatsOk = vkGetPhysicalDeviceSurfaceFormatsKHR(renderer->physicalDevice,
                                                           renderer->surface, &formatCount,
                                                           formats) == VK_SUCCESS;
    if (!formatsOk) {
        free(formats);
        return false;
    }
    VkSurfaceFormatKHR selectedFormat = choose_surface_format(formats, formatCount);
    free(formats);
    if (renderer->swapchain != VK_NULL_HANDLE &&
        renderer->swapchainFormat != selectedFormat.format) {
        destroy_swapchain(renderer);
        if (renderer->pipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(renderer->device, renderer->pipeline, NULL);
            renderer->pipeline = VK_NULL_HANDLE;
        }
        if (renderer->pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(renderer->device, renderer->pipelineLayout, NULL);
            renderer->pipelineLayout = VK_NULL_HANDLE;
        }
        if (renderer->renderPass != VK_NULL_HANDLE) {
            vkDestroyRenderPass(renderer->device, renderer->renderPass, NULL);
            renderer->renderPass = VK_NULL_HANDLE;
        }
        if (!create_render_pass(renderer, selectedFormat.format) || !create_pipeline(renderer)) {
            return false;
        }
    }

    uint32_t presentModeCount = 0;
    if (!vk_succeeded(vkGetPhysicalDeviceSurfacePresentModesKHR(renderer->physicalDevice,
                                                                 renderer->surface,
                                                                 &presentModeCount, NULL),
                      "vkGetPhysicalDeviceSurfacePresentModesKHR(count)")) {
        return false;
    }
    VkPresentModeKHR presentMode = VK_PRESENT_MODE_FIFO_KHR;
    if (presentModeCount > 0) {
        VkPresentModeKHR *presentModes = calloc(presentModeCount, sizeof(*presentModes));
        if (presentModes != NULL && vkGetPhysicalDeviceSurfacePresentModesKHR(
                renderer->physicalDevice, renderer->surface, &presentModeCount,
                presentModes) == VK_SUCCESS) {
            // MAILBOX reduces touch-to-pixel latency when the Android compositor exposes it.
            for (uint32_t i = 0; i < presentModeCount; ++i) {
                if (presentModes[i] == VK_PRESENT_MODE_MAILBOX_KHR) {
                    presentMode = VK_PRESENT_MODE_MAILBOX_KHR;
                    break;
                }
            }
        }
        free(presentModes);
    }
    VkExtent2D extent;
    if (capabilities.currentExtent.width != UINT32_MAX) {
        extent = capabilities.currentExtent;
    } else {
        extent.width = clamp_u32(requestedWidth, capabilities.minImageExtent.width,
                                 capabilities.maxImageExtent.width);
        extent.height = clamp_u32(requestedHeight, capabilities.minImageExtent.height,
                                  capabilities.maxImageExtent.height);
    }
    if (extent.width == 0 || extent.height == 0) return false;

    uint32_t imageCount = capabilities.minImageCount + 1;
    if (capabilities.maxImageCount != 0 && imageCount > capabilities.maxImageCount) {
        imageCount = capabilities.maxImageCount;
    }
    VkSwapchainCreateInfoKHR info = {
        .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
        .surface = renderer->surface,
        .minImageCount = imageCount,
        .imageFormat = selectedFormat.format,
        .imageColorSpace = selectedFormat.colorSpace,
        .imageExtent = extent,
        .imageArrayLayers = 1,
        .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
        .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .preTransform = capabilities.currentTransform,
        .compositeAlpha = choose_composite_alpha(capabilities.supportedCompositeAlpha),
        .presentMode = presentMode,
        .clipped = VK_TRUE,
        .oldSwapchain = renderer->swapchain,
    };
    VkSwapchainKHR newSwapchain = VK_NULL_HANDLE;
    if (!vk_succeeded(vkCreateSwapchainKHR(renderer->device, &info, NULL, &newSwapchain),
                      "vkCreateSwapchainKHR")) {
        return false;
    }
    uint32_t actualCount = 0;
    VkResult imageResult = vkGetSwapchainImagesKHR(renderer->device, newSwapchain,
                                                    &actualCount, NULL);
    if (imageResult != VK_SUCCESS || actualCount == 0) {
        vkDestroySwapchainKHR(renderer->device, newSwapchain, NULL);
        return false;
    }
    VkImage *images = calloc(actualCount, sizeof(*images));
    VkImageView *views = calloc(actualCount, sizeof(*views));
    VkFramebuffer *framebuffers = calloc(actualCount, sizeof(*framebuffers));
    LOGI("swapchain extent=%ux%u images=%u transform=0x%x presentMode=%d",
         extent.width, extent.height, actualCount, (unsigned int) capabilities.currentTransform,
         (int) presentMode);
    if (images == NULL || views == NULL || framebuffers == NULL ||
        vkGetSwapchainImagesKHR(renderer->device, newSwapchain, &actualCount, images) != VK_SUCCESS) {
        free(images);
        free(views);
        free(framebuffers);
        vkDestroySwapchainKHR(renderer->device, newSwapchain, NULL);
        return false;
    }
    for (uint32_t i = 0; i < actualCount; ++i) {
        VkImageViewCreateInfo viewInfo = {
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .image = images[i],
            .viewType = VK_IMAGE_VIEW_TYPE_2D,
            .format = selectedFormat.format,
            .subresourceRange = {
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .baseMipLevel = 0,
                .levelCount = 1,
                .baseArrayLayer = 0,
                .layerCount = 1,
            },
        };
        if (!vk_succeeded(vkCreateImageView(renderer->device, &viewInfo, NULL, &views[i]),
                          "vkCreateSwapchainImageView")) {
            for (uint32_t j = 0; j < i; ++j) vkDestroyImageView(renderer->device, views[j], NULL);
            free(images);
            free(views);
            free(framebuffers);
            vkDestroySwapchainKHR(renderer->device, newSwapchain, NULL);
            return false;
        }
        VkImageView attachments[] = { views[i] };
        VkFramebufferCreateInfo framebufferInfo = {
            .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
            .renderPass = renderer->renderPass,
            .attachmentCount = 1,
            .pAttachments = attachments,
            .width = extent.width,
            .height = extent.height,
            .layers = 1,
        };
        if (!vk_succeeded(vkCreateFramebuffer(renderer->device, &framebufferInfo, NULL,
                                              &framebuffers[i]),
                          "vkCreateFramebuffer")) {
            vkDestroyImageView(renderer->device, views[i], NULL);
            for (uint32_t j = 0; j < i; ++j) {
                vkDestroyFramebuffer(renderer->device, framebuffers[j], NULL);
                vkDestroyImageView(renderer->device, views[j], NULL);
            }
            free(images);
            free(views);
            free(framebuffers);
            vkDestroySwapchainKHR(renderer->device, newSwapchain, NULL);
            return false;
        }
    }
    destroy_swapchain(renderer);
    renderer->swapchain = newSwapchain;
    renderer->swapchainFormat = selectedFormat.format;
    renderer->extent = extent;
    renderer->swapchainImageCount = actualCount;
    renderer->swapchainImages = images;
    renderer->swapchainViews = views;
    renderer->framebuffers = framebuffers;
    renderer->requestedWidth = requestedWidth;
    renderer->requestedHeight = requestedHeight;
    renderer->swapchainRecreates++;
    return true;
}

static bool choose_physical_device(TermuxRenderer *renderer) {
    uint32_t count = 0;
    if (!vk_succeeded(vkEnumeratePhysicalDevices(renderer->instance, &count, NULL),
                      "vkEnumeratePhysicalDevices(count)")) {
        return false;
    }
    if (count == 0) {
        LOGE("no Vulkan physical devices");
        return false;
    }
    VkPhysicalDevice *devices = calloc(count, sizeof(*devices));
    if (devices == NULL || vkEnumeratePhysicalDevices(renderer->instance, &count, devices) != VK_SUCCESS) {
        free(devices);
        return false;
    }
    bool selected = false;
    for (uint32_t i = 0; i < count && !selected; ++i) {
        if (!has_device_extension(devices[i], VK_KHR_SWAPCHAIN_EXTENSION_NAME)) continue;
        uint32_t queueCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &queueCount, NULL);
        if (queueCount == 0) continue;
        VkQueueFamilyProperties *queues = calloc(queueCount, sizeof(*queues));
        if (queues == NULL) continue;
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &queueCount, queues);
        for (uint32_t q = 0; q < queueCount; ++q) {
            if ((queues[q].queueFlags & VK_QUEUE_GRAPHICS_BIT) == 0) continue;
            VkBool32 present = VK_FALSE;
            if (vkGetPhysicalDeviceSurfaceSupportKHR(devices[i], q, renderer->surface,
                                                     &present) != VK_SUCCESS || !present) {
                continue;
            }
            renderer->physicalDevice = devices[i];
            renderer->queueFamily = q;
            selected = true;
            break;
        }
        free(queues);
    }
    free(devices);
    if (!selected) {
        LOGE("no graphics+present Vulkan queue");
        return false;
    }
    VkPhysicalDeviceProperties properties;
    vkGetPhysicalDeviceProperties(renderer->physicalDevice, &properties);
    renderer->stagingAlignment = 4u;
    if (properties.limits.optimalBufferCopyOffsetAlignment > renderer->stagingAlignment) {
        renderer->stagingAlignment = properties.limits.optimalBufferCopyOffsetAlignment;
    }
    if (properties.limits.nonCoherentAtomSize > renderer->stagingAlignment) {
        renderer->stagingAlignment = properties.limits.nonCoherentAtomSize;
    }
    if (properties.limits.minMemoryMapAlignment > renderer->stagingAlignment) {
        renderer->stagingAlignment = properties.limits.minMemoryMapAlignment;
    }
    LOGI("selected Vulkan device=%s api=%u driver=%u queue=%u stagingAlign=%llu",
         properties.deviceName, properties.apiVersion, properties.driverVersion,
         renderer->queueFamily, (unsigned long long) renderer->stagingAlignment);
    return true;
}

static bool create_device(TermuxRenderer *renderer) {
    float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = renderer->queueFamily,
        .queueCount = 1,
        .pQueuePriorities = &priority,
    };
    const char *extensions[] = { VK_KHR_SWAPCHAIN_EXTENSION_NAME };
    VkPhysicalDeviceFeatures features;
    memset(&features, 0, sizeof(features));
    VkDeviceCreateInfo info = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &queueInfo,
        .enabledExtensionCount = 1,
        .ppEnabledExtensionNames = extensions,
        .pEnabledFeatures = &features,
    };
    if (!vk_succeeded(vkCreateDevice(renderer->physicalDevice, &info, NULL, &renderer->device),
                      "vkCreateDevice")) {
        return false;
    }
    vkGetDeviceQueue(renderer->device, renderer->queueFamily, 0, &renderer->queue);
    return renderer->queue != VK_NULL_HANDLE;
}

static bool create_frame_resources(TermuxRenderer *renderer) {
    VkCommandPoolCreateInfo poolInfo = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        .queueFamilyIndex = renderer->queueFamily,
    };
    if (!vk_succeeded(vkCreateCommandPool(renderer->device, &poolInfo, NULL,
                                          &renderer->commandPool),
                      "vkCreateCommandPool")) {
        return false;
    }
    VkCommandBufferAllocateInfo commandInfo = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = renderer->commandPool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = TERMUX_FRAMES_IN_FLIGHT,
    };
    VkCommandBuffer commands[TERMUX_FRAMES_IN_FLIGHT];
    if (!vk_succeeded(vkAllocateCommandBuffers(renderer->device, &commandInfo, commands),
                      "vkAllocateCommandBuffers")) {
        return false;
    }
    for (uint32_t i = 0; i < TERMUX_FRAMES_IN_FLIGHT; ++i) {
        TermuxFrameResources *frame = &renderer->frames[i];
        memset(frame, 0, sizeof(*frame));
        frame->commandBuffer = commands[i];
        /* Staging is allocated lazily from the exact dirty atlas footprint. Most retained frames
         * upload no pixels, and a second in-flight frame must not reserve 12 MiB just in case. */
        if (!create_buffer(renderer, TERMUX_INITIAL_VERTEX_BYTES,
                           VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                           VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                           &frame->vertex)) {
            return false;
        }
        VkSemaphoreCreateInfo semaphoreInfo = {
            .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
        };
        if (!vk_succeeded(vkCreateSemaphore(renderer->device, &semaphoreInfo, NULL,
                                             &frame->imageAvailable),
                          "vkCreateSemaphore(imageAvailable)") ||
            !vk_succeeded(vkCreateSemaphore(renderer->device, &semaphoreInfo, NULL,
                                             &frame->renderFinished),
                          "vkCreateSemaphore(renderFinished)")) {
            return false;
        }
        VkFenceCreateInfo fenceInfo = {
            .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
            .flags = VK_FENCE_CREATE_SIGNALED_BIT,
        };
        if (!vk_succeeded(vkCreateFence(renderer->device, &fenceInfo, NULL, &frame->fence),
                          "vkCreateFence")) {
            return false;
        }
    }
    return true;
}

static void destroy_frame_resources(TermuxRenderer *renderer) {
    if (renderer->device == VK_NULL_HANDLE) return;
    for (uint32_t i = 0; i < TERMUX_FRAMES_IN_FLIGHT; ++i) {
        TermuxFrameResources *frame = &renderer->frames[i];
        if (frame->fence != VK_NULL_HANDLE) vkDestroyFence(renderer->device, frame->fence, NULL);
        if (frame->imageAvailable != VK_NULL_HANDLE) {
            vkDestroySemaphore(renderer->device, frame->imageAvailable, NULL);
        }
        if (frame->renderFinished != VK_NULL_HANDLE) {
            vkDestroySemaphore(renderer->device, frame->renderFinished, NULL);
        }
        destroy_buffer(renderer, &frame->staging);
        destroy_buffer(renderer, &frame->vertex);
        memset(frame, 0, sizeof(*frame));
    }
    if (renderer->commandPool != VK_NULL_HANDLE) {
        vkDestroyCommandPool(renderer->device, renderer->commandPool, NULL);
        renderer->commandPool = VK_NULL_HANDLE;
    }
}

static bool align_staging_offset(size_t value, VkDeviceSize alignment, size_t *aligned) {
    if (aligned == NULL) return false;
    size_t safeAlignment = alignment == 0 || alignment > SIZE_MAX
        ? 4u : (size_t) alignment;
    if (safeAlignment < 4u) safeAlignment = 4u;
    size_t remainder = value % safeAlignment;
    if (remainder == 0u) {
        *aligned = value;
        return true;
    }
    size_t padding = safeAlignment - remainder;
    if (value > SIZE_MAX - padding) return false;
    *aligned = value + padding;
    return true;
}

static bool ensure_staging_capacity(TermuxRenderer *renderer, TermuxFrameResources *frame,
                                    size_t required) {
    if (required == 0u || required <= frame->staging.size) return true;
    if (required > TERMUX_MAX_STAGING_BYTES) {
        LOGE("atlas staging batch too large bytes=%zu", required);
        return false;
    }
    VkDeviceSize next = frame->staging.size == 0
        ? TERMUX_INITIAL_STAGING_BYTES : frame->staging.size;
    while (next < required && next < TERMUX_MAX_STAGING_BYTES) next *= 2u;
    if (next > TERMUX_MAX_STAGING_BYTES) next = TERMUX_MAX_STAGING_BYTES;
    if (next < required) return false;

    TermuxBuffer replacement;
    if (!create_buffer(renderer, next, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                       VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                           VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                       &replacement)) {
        return false;
    }
    destroy_buffer(renderer, &frame->staging);
    frame->staging = replacement;
    return true;
}

static bool ensure_vertex_capacity(TermuxRenderer *renderer, TermuxFrameResources *frame,
                                   size_t required) {
    if (required <= frame->vertex.size) return true;
    if (required > TERMUX_MAX_VERTEX_BYTES) {
        LOGE("vertex batch too large bytes=%zu", required);
        return false;
    }
    VkDeviceSize next = frame->vertex.size == 0 ? TERMUX_INITIAL_VERTEX_BYTES : frame->vertex.size;
    while (next < required) next *= 2;
    if (next > TERMUX_MAX_VERTEX_BYTES) next = TERMUX_MAX_VERTEX_BYTES;
    TermuxBuffer replacement;
    if (!create_buffer(renderer, next, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                       VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                       &replacement)) {
        return false;
    }
    destroy_buffer(renderer, &frame->vertex);
    frame->vertex = replacement;
    frame->vertexValid = false;
    return true;
}

static void image_barrier(VkCommandBuffer commandBuffer, VkImage image,
                          VkImageLayout oldLayout, VkImageLayout newLayout) {
    VkAccessFlags sourceAccess = 0;
    VkAccessFlags destinationAccess = 0;
    VkPipelineStageFlags sourceStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
    VkPipelineStageFlags destinationStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    if (oldLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
        sourceAccess = VK_ACCESS_SHADER_READ_BIT;
        sourceStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    } else if (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
        sourceAccess = VK_ACCESS_TRANSFER_WRITE_BIT;
        sourceStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    }
    if (newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
        destinationAccess = VK_ACCESS_TRANSFER_WRITE_BIT;
        destinationStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    } else if (newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
        destinationAccess = VK_ACCESS_SHADER_READ_BIT;
        destinationStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    }
    VkImageMemoryBarrier barrier = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = sourceAccess,
        .dstAccessMask = destinationAccess,
        .oldLayout = oldLayout,
        .newLayout = newLayout,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = image,
        .subresourceRange = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .baseMipLevel = 0,
            .levelCount = 1,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
    };
    vkCmdPipelineBarrier(commandBuffer, sourceStage, destinationStage, 0,
                         0, NULL, 0, NULL, 1, &barrier);
}

static bool bitmap_info(JNIEnv *env, jobject bitmap, AndroidBitmapInfo *info) {
    if (bitmap == NULL || AndroidBitmap_getInfo(env, bitmap, info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_getInfo failed");
        return false;
    }
    if (info->format != ANDROID_BITMAP_FORMAT_A_8 &&
        info->format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("unsupported bitmap format=%u", info->format);
        return false;
    }
    return true;
}

static bool compute_upload_region(const AndroidBitmapInfo *info, int generation,
                                  int dirtyLeft, int dirtyTop,
                                  int dirtyRight, int dirtyBottom,
                                  const TermuxTexture *texture, bool maskTexture,
                                  TermuxUploadRegion *region) {
    if (info == NULL || texture == NULL || region == NULL ||
        info->width > INT32_MAX || info->height > INT32_MAX) return false;
    memset(region, 0, sizeof(*region));
    bool fullUpload = generation < 0 || texture->layout == VK_IMAGE_LAYOUT_UNDEFINED;
    int bitmapWidth = (int) info->width;
    int bitmapHeight = (int) info->height;
    int left = fullUpload ? 0 : dirtyLeft;
    int top = fullUpload ? 0 : dirtyTop;
    int right = fullUpload ? bitmapWidth : dirtyRight;
    int bottom = fullUpload ? bitmapHeight : dirtyBottom;
    left = left < 0 ? 0 : left;
    top = top < 0 ? 0 : top;
    right = right > bitmapWidth ? bitmapWidth : right;
    bottom = bottom > bitmapHeight ? bitmapHeight : bottom;
    if (right <= left || bottom <= top) return true;

    size_t bytesPerPixel = maskTexture ? 1u : 4u;
    size_t width = (size_t) (right - left);
    size_t height = (size_t) (bottom - top);
    if (width > SIZE_MAX / bytesPerPixel) return false;
    size_t rowBytes = width * bytesPerPixel;
    if (height > SIZE_MAX / rowBytes) return false;
    region->left = left;
    region->top = top;
    region->right = right;
    region->bottom = bottom;
    region->bytesPerPixel = bytesPerPixel;
    region->rowBytes = rowBytes;
    region->uploadBytes = rowBytes * height;
    return true;
}

static bool upload_bitmap(JNIEnv *env, TermuxRenderer *renderer, TermuxFrameResources *frame,
                          VkCommandBuffer commandBuffer, jobject bitmap,
                          TermuxTexture *texture, size_t stagingOffset,
                          const TermuxUploadRegion *region) {
    AndroidBitmapInfo info;
    if (region == NULL) return false;
    if (region->uploadBytes == 0u) return true;
    if (!bitmap_info(env, bitmap, &info)) return false;
    int left = region->left;
    int top = region->top;
    int right = region->right;
    int bottom = region->bottom;
    size_t bytesPerPixel = region->bytesPerPixel;
    size_t rowBytes = region->rowBytes;
    size_t uploadBytes = region->uploadBytes;
    if (stagingOffset > SIZE_MAX - uploadBytes ||
        stagingOffset + uploadBytes > frame->staging.size) {
        LOGE("staging region too small bytes=%zu offset=%zu", uploadBytes, stagingOffset);
        return false;
    }
    void *pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        pixels == NULL) {
        LOGE("AndroidBitmap_lockPixels failed");
        return false;
    }
    void *mapped = NULL;
    bool copied = vkMapMemory(renderer->device, frame->staging.memory, stagingOffset,
                              uploadBytes, 0, &mapped) == VK_SUCCESS;
    if (!copied || mapped == NULL) {
        LOGE("vkMapMemory(staging) failed");
        AndroidBitmap_unlockPixels(env, bitmap);
        return false;
    }
    uint8_t *destination = (uint8_t *) mapped;
    const uint8_t *source = (const uint8_t *) pixels;
    for (int y = top; y < bottom; ++y) {
        memcpy(destination + (size_t) (y - top) * rowBytes,
               source + (size_t) y * info.stride + (size_t) left * bytesPerPixel,
               rowBytes);
    }
    /* create_buffer requires HOST_COHERENT, so unmap is sufficient publication. */
    vkUnmapMemory(renderer->device, frame->staging.memory);
    AndroidBitmap_unlockPixels(env, bitmap);

    if (texture->layout != VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
        image_barrier(commandBuffer, texture->image, texture->layout,
                      VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    }
    VkBufferImageCopy copy = {
        .bufferOffset = stagingOffset,
        .bufferRowLength = (uint32_t) (right - left),
        .bufferImageHeight = (uint32_t) (bottom - top),
        .imageSubresource = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .mipLevel = 0,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
        .imageOffset = { left, top, 0 },
        .imageExtent = { (uint32_t) (right - left), (uint32_t) (bottom - top), 1 },
    };
    vkCmdCopyBufferToImage(commandBuffer, frame->staging.buffer, texture->image,
                           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
    image_barrier(commandBuffer, texture->image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                  VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    texture->layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    renderer->uploadedRegions++;
    return true;
}

static bool upload_vertex_data(TermuxRenderer *renderer, TermuxFrameResources *frame,
                               const void *data, size_t bytes, uint64_t generation) {
    if (frame->vertexValid && frame->vertexGeneration == generation &&
        frame->vertexBytes == bytes) {
        renderer->vertexUploadReuses++;
        return true;
    }
    if (bytes == 0) {
        frame->vertexGeneration = generation;
        frame->vertexBytes = 0;
        frame->vertexValid = true;
        return true;
    }
    if (!ensure_vertex_capacity(renderer, frame, bytes)) return false;
    void *mapped = NULL;
    if (vkMapMemory(renderer->device, frame->vertex.memory, 0, bytes, 0, &mapped) != VK_SUCCESS ||
        mapped == NULL) {
        LOGE("vkMapMemory(vertex) failed");
        return false;
    }
    memcpy(mapped, data, bytes);
    /* Vertex memory is explicitly HOST_COHERENT. */
    vkUnmapMemory(renderer->device, frame->vertex.memory);
    frame->vertexGeneration = generation;
    frame->vertexBytes = bytes;
    frame->vertexValid = true;
    renderer->vertexUploads++;
    return true;
}

static VkClearColorValue clear_color_from_argb(int color) {
    VkClearColorValue clear;
    clear.float32[0] = (float) ((color >> 16) & 0xff) / 255.0f;
    clear.float32[1] = (float) ((color >> 8) & 0xff) / 255.0f;
    clear.float32[2] = (float) (color & 0xff) / 255.0f;
    clear.float32[3] = (float) ((color >> 24) & 0xff) / 255.0f;
    return clear;
}

static bool create_instance(TermuxRenderer *renderer) {
    const char *extensions[] = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
    };
    VkApplicationInfo application = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "Termux",
        .applicationVersion = 1,
        .pEngineName = "TermuxTerminalRenderer",
        .engineVersion = 1,
        .apiVersion = VK_API_VERSION_1_0,
    };
    VkInstanceCreateInfo info = {
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &application,
        .enabledExtensionCount = (uint32_t) (sizeof(extensions) / sizeof(extensions[0])),
        .ppEnabledExtensionNames = extensions,
    };
    return vk_succeeded(vkCreateInstance(&info, NULL, &renderer->instance), "vkCreateInstance");
}

static bool create_surface(TermuxRenderer *renderer) {
    VkAndroidSurfaceCreateInfoKHR info = {
        .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
        .window = renderer->window,
    };
    PFN_vkCreateAndroidSurfaceKHR createSurface =
        (PFN_vkCreateAndroidSurfaceKHR) vkGetInstanceProcAddr(renderer->instance,
                                                                "vkCreateAndroidSurfaceKHR");
    if (createSurface == NULL) {
        LOGE("vkCreateAndroidSurfaceKHR unavailable");
        return false;
    }
    return vk_succeeded(createSurface(renderer->instance, &info, NULL, &renderer->surface),
                        "vkCreateAndroidSurfaceKHR");
}

static bool create_renderer(TermuxRenderer *renderer, ANativeWindow *window,
                            uint32_t width, uint32_t height) {
    memset(renderer, 0, sizeof(*renderer));
    renderer->window = window;
    if (!create_instance(renderer) || !create_surface(renderer) ||
        !choose_physical_device(renderer) || !create_device(renderer) ||
        !create_descriptor_state(renderer) ||
        !create_texture(renderer, VK_FORMAT_R8_UNORM, 1, 1, &renderer->maskTexture) ||
        !create_texture(renderer, VK_FORMAT_R8G8B8A8_UNORM, 1, 1, &renderer->colorTexture) ||
        !create_texture(renderer, VK_FORMAT_R8_UNORM, 1, 1, &renderer->runMaskTexture) ||
        !update_descriptor_state(renderer) || !create_render_pass(renderer, VK_FORMAT_R8G8B8A8_UNORM)) {
        return false;
    }
    // The render pass format is selected by the surface. Rebuild it if the first choice differs.
    VkSurfaceCapabilitiesKHR capabilities;
    if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(renderer->physicalDevice, renderer->surface,
                                                   &capabilities) != VK_SUCCESS) return false;
    uint32_t formatCount = 0;
    if (vkGetPhysicalDeviceSurfaceFormatsKHR(renderer->physicalDevice, renderer->surface,
                                             &formatCount, NULL) != VK_SUCCESS || formatCount == 0) {
        return false;
    }
    VkSurfaceFormatKHR *formats = calloc(formatCount, sizeof(*formats));
    if (formats == NULL || vkGetPhysicalDeviceSurfaceFormatsKHR(renderer->physicalDevice,
                                                                 renderer->surface, &formatCount,
                                                                 formats) != VK_SUCCESS) {
        free(formats);
        return false;
    }
    VkFormat surfaceFormat = choose_surface_format(formats, formatCount).format;
    free(formats);
    if (surfaceFormat != VK_FORMAT_R8G8B8A8_UNORM) {
        vkDestroyRenderPass(renderer->device, renderer->renderPass, NULL);
        renderer->renderPass = VK_NULL_HANDLE;
        if (!create_render_pass(renderer, surfaceFormat)) return false;
    }
    renderer->swapchainFormat = surfaceFormat;
    if (!create_pipeline(renderer) || !create_frame_resources(renderer) ||
        !create_swapchain(renderer, width, height)) {
        return false;
    }
    renderer->initialized = true;
    LOGI("renderer-created size=%ux%u format=%d", width, height, (int) surfaceFormat);
    return true;
}

static void destroy_renderer(TermuxRenderer *renderer) {
    if (renderer == NULL) return;
    if (renderer->device != VK_NULL_HANDLE) vkDeviceWaitIdle(renderer->device);
    if (renderer->device != VK_NULL_HANDLE) {
        destroy_swapchain(renderer);
        destroy_frame_resources(renderer);
        destroy_texture(renderer, &renderer->maskTexture);
        destroy_texture(renderer, &renderer->colorTexture);
        destroy_texture(renderer, &renderer->runMaskTexture);
        if (renderer->pipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(renderer->device, renderer->pipeline, NULL);
        }
        if (renderer->pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(renderer->device, renderer->pipelineLayout, NULL);
        }
        if (renderer->renderPass != VK_NULL_HANDLE) {
            vkDestroyRenderPass(renderer->device, renderer->renderPass, NULL);
        }
        if (renderer->sampler != VK_NULL_HANDLE) {
            vkDestroySampler(renderer->device, renderer->sampler, NULL);
        }
        if (renderer->descriptorPool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(renderer->device, renderer->descriptorPool, NULL);
        }
        if (renderer->descriptorSetLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(renderer->device, renderer->descriptorSetLayout, NULL);
        }
        vkDestroyDevice(renderer->device, NULL);
    }
    if (renderer->surface != VK_NULL_HANDLE && renderer->instance != VK_NULL_HANDLE) {
        vkDestroySurfaceKHR(renderer->instance, renderer->surface, NULL);
    }
    if (renderer->instance != VK_NULL_HANDLE) vkDestroyInstance(renderer->instance, NULL);
    if (renderer->window != NULL) ANativeWindow_release(renderer->window);
    memset(renderer, 0, sizeof(*renderer));
}

static bool ensure_swapchain(TermuxRenderer *renderer, uint32_t width, uint32_t height) {
    if (renderer->swapchain != VK_NULL_HANDLE && renderer->requestedWidth == width &&
        renderer->requestedHeight == height && renderer->extent.width == width &&
        renderer->extent.height == height) return true;
    if (renderer->device != VK_NULL_HANDLE) vkDeviceWaitIdle(renderer->device);
    return create_swapchain(renderer, width, height);
}

static bool ensure_atlas_textures(TermuxRenderer *renderer,
                                  const AndroidBitmapInfo *maskInfo,
                                  const AndroidBitmapInfo *colorInfo,
                                  const AndroidBitmapInfo *runMaskInfo) {
    bool maskChanged = renderer->maskTexture.image == VK_NULL_HANDLE ||
        renderer->maskTexture.width != maskInfo->width ||
        renderer->maskTexture.height != maskInfo->height;
    bool colorChanged = renderer->colorTexture.image == VK_NULL_HANDLE ||
        renderer->colorTexture.width != colorInfo->width ||
        renderer->colorTexture.height != colorInfo->height;
    bool runMaskChanged = renderer->runMaskTexture.image == VK_NULL_HANDLE ||
        renderer->runMaskTexture.width != runMaskInfo->width ||
        renderer->runMaskTexture.height != runMaskInfo->height;
    if (!maskChanged && !colorChanged && !runMaskChanged) return true;
    if (renderer->device != VK_NULL_HANDLE) vkDeviceWaitIdle(renderer->device);
    if (maskChanged) {
        destroy_texture(renderer, &renderer->maskTexture);
        if (!create_texture(renderer, VK_FORMAT_R8_UNORM, maskInfo->width, maskInfo->height,
                            &renderer->maskTexture)) return false;
    }
    if (colorChanged) {
        destroy_texture(renderer, &renderer->colorTexture);
        if (!create_texture(renderer, VK_FORMAT_R8G8B8A8_UNORM,
                            colorInfo->width, colorInfo->height,
                            &renderer->colorTexture)) return false;
    }
    if (runMaskChanged) {
        destroy_texture(renderer, &renderer->runMaskTexture);
        if (!create_texture(renderer, VK_FORMAT_R8_UNORM,
                            runMaskInfo->width, runMaskInfo->height,
                            &renderer->runMaskTexture)) return false;
    }
    return update_descriptor_state(renderer);
}

static bool force_recreate_swapchain(TermuxRenderer *renderer, uint32_t width,
                                     uint32_t height) {
    if (renderer->device != VK_NULL_HANDLE) vkDeviceWaitIdle(renderer->device);
    return create_swapchain(renderer, width, height);
}

static int render_frame(JNIEnv *env, TermuxRenderer *renderer, uint32_t width, uint32_t height,
                        int backgroundColor, float viewportYOffset,
                        jobject vertices, int vertexBytes, uint64_t vertexGeneration,
                        jobject maskBitmap, int maskGeneration, int maskLeft, int maskTop,
                        int maskRight, int maskBottom, jobject colorBitmap, int colorGeneration,
                        int colorLeft, int colorTop, int colorRight, int colorBottom,
                        jobject runMaskBitmap, int runMaskGeneration,
                        int runMaskLeft, int runMaskTop,
                        int runMaskRight, int runMaskBottom) {
    if (renderer == NULL || !renderer->initialized || vertices == NULL || vertexBytes < 0) {
        return -1;
    }
    AndroidBitmapInfo maskInfo;
    AndroidBitmapInfo colorInfo;
    AndroidBitmapInfo runMaskInfo;
    if (!bitmap_info(env, maskBitmap, &maskInfo) ||
        !bitmap_info(env, colorBitmap, &colorInfo) ||
        !bitmap_info(env, runMaskBitmap, &runMaskInfo) ||
        maskInfo.format != ANDROID_BITMAP_FORMAT_A_8 ||
        colorInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        runMaskInfo.format != ANDROID_BITMAP_FORMAT_A_8 ||
        !ensure_atlas_textures(renderer, &maskInfo, &colorInfo, &runMaskInfo)) {
        return -1;
    }
    TermuxUploadRegion maskUpload;
    TermuxUploadRegion colorUpload;
    TermuxUploadRegion runMaskUpload;
    if (!compute_upload_region(&maskInfo, maskGeneration,
                               maskLeft, maskTop, maskRight, maskBottom,
                               &renderer->maskTexture, true, &maskUpload) ||
        !compute_upload_region(&colorInfo, colorGeneration,
                               colorLeft, colorTop, colorRight, colorBottom,
                               &renderer->colorTexture, false, &colorUpload) ||
        !compute_upload_region(&runMaskInfo, runMaskGeneration,
                               runMaskLeft, runMaskTop, runMaskRight, runMaskBottom,
                               &renderer->runMaskTexture, true, &runMaskUpload)) {
        LOGE("invalid atlas upload region");
        return -1;
    }
    size_t maskOffset = 0u;
    size_t colorOffset;
    size_t runMaskOffset;
    size_t stagingBytes;
    if (!align_staging_offset(maskUpload.uploadBytes, renderer->stagingAlignment,
                              &colorOffset) ||
        colorOffset > SIZE_MAX - colorUpload.uploadBytes ||
        !align_staging_offset(colorOffset + colorUpload.uploadBytes,
                              renderer->stagingAlignment, &runMaskOffset) ||
        runMaskOffset > SIZE_MAX - runMaskUpload.uploadBytes) {
        LOGE("atlas staging size overflow");
        return -1;
    }
    stagingBytes = runMaskOffset + runMaskUpload.uploadBytes;
    void *vertexAddress = (*env)->GetDirectBufferAddress(env, vertices);
    jlong vertexCapacity = (*env)->GetDirectBufferCapacity(env, vertices);
    if (vertexAddress == NULL || vertexCapacity < vertexBytes ||
        vertexBytes % TERMUX_INSTANCE_BYTES != 0) {
        LOGE("invalid instance buffer address=%p capacity=%lld bytes=%d", vertexAddress,
             (long long) vertexCapacity, vertexBytes);
        return -1;
    }
    if (!ensure_swapchain(renderer, width, height)) return -1;
    // SurfaceTexture and View layout do not commit atomically during IME/rotation animation.
    // Retry instead of presenting an old-sized swapchain and labelling it with the new frame size.
    if (renderer->extent.width != width || renderer->extent.height != height) {
        renderer->retryCount++;
        return 0;
    }
    TermuxFrameResources *frame = &renderer->frames[renderer->currentFrame];
    VkResult waitResult = vkWaitForFences(renderer->device, 1, &frame->fence, VK_TRUE,
                                          UINT64_MAX);
    if (!vk_succeeded(waitResult, "vkWaitForFences")) return -1;
    if (!ensure_staging_capacity(renderer, frame, stagingBytes)) return -1;
    uint32_t imageIndex = 0;
    VkResult acquire = vkAcquireNextImageKHR(renderer->device, renderer->swapchain, UINT64_MAX,
                                             frame->imageAvailable, VK_NULL_HANDLE, &imageIndex);
    if (acquire == VK_ERROR_OUT_OF_DATE_KHR) {
        renderer->retryCount++;
        if (!force_recreate_swapchain(renderer, width, height)) return -1;
        return 0;
    }
    if (acquire != VK_SUCCESS && acquire != VK_SUBOPTIMAL_KHR) {
        LOGE("vkAcquireNextImageKHR failed=%d", (int) acquire);
        return -1;
    }
    if (!upload_vertex_data(renderer, frame, vertexAddress, (size_t) vertexBytes,
                            vertexGeneration)) return -1;
    VkCommandBuffer commandBuffer = frame->commandBuffer;
    if (!vk_succeeded(vkResetCommandBuffer(commandBuffer, 0), "vkResetCommandBuffer") ||
        !vk_succeeded(vkBeginCommandBuffer(commandBuffer, &(VkCommandBufferBeginInfo) {
            .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        }), "vkBeginCommandBuffer")) return -1;

    // Atlas uploads and terminal drawing share one ordered command buffer, so no CPU/GPU
    // race can expose a half-updated glyph atlas.
    if (!upload_bitmap(env, renderer, frame, commandBuffer, maskBitmap,
                       &renderer->maskTexture, maskOffset, &maskUpload) ||
        !upload_bitmap(env, renderer, frame, commandBuffer, colorBitmap,
                       &renderer->colorTexture, colorOffset, &colorUpload) ||
        !upload_bitmap(env, renderer, frame, commandBuffer, runMaskBitmap,
                       &renderer->runMaskTexture, runMaskOffset, &runMaskUpload)) {
        vkEndCommandBuffer(commandBuffer);
        return -1;
    }
    // record_frame begins a command buffer itself; the upload path above already began it.
    // Keep the render commands inline here to preserve upload ordering.
    VkRenderPassBeginInfo renderPass = {
        .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
        .renderPass = renderer->renderPass,
        .framebuffer = renderer->framebuffers[imageIndex],
        .renderArea = { { 0, 0 }, { renderer->extent.width, renderer->extent.height } },
        .clearValueCount = 1,
        .pClearValues = &(VkClearValue) { .color = clear_color_from_argb(backgroundColor) },
    };
    vkCmdBeginRenderPass(commandBuffer, &renderPass, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, renderer->pipeline);
    vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                            renderer->pipelineLayout, 0, 1, &renderer->descriptorSet, 0, NULL);
    VkViewport viewport = {
        .x = 0.0f, .y = 0.0f, .width = (float) renderer->extent.width,
        .height = (float) renderer->extent.height, .minDepth = 0.0f, .maxDepth = 1.0f,
    };
    VkRect2D scissor = { { 0, 0 }, renderer->extent };
    vkCmdSetViewport(commandBuffer, 0, 1, &viewport);
    vkCmdSetScissor(commandBuffer, 0, 1, &scissor);
    float pushConstants[8] = {
        (float) renderer->extent.width,
        (float) renderer->extent.height,
        viewportYOffset,
        0.0f,
        (float) maskInfo.width,
        (float) colorInfo.width,
        (float) runMaskInfo.width,
        (float) runMaskInfo.height,
    };
    vkCmdPushConstants(commandBuffer, renderer->pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT,
                       0, sizeof(pushConstants), pushConstants);
    VkDeviceSize vertexOffset = 0;
    vkCmdBindVertexBuffers(commandBuffer, 0, 1, &frame->vertex.buffer, &vertexOffset);
    if (vertexBytes > 0) {
        vkCmdDraw(commandBuffer, TERMUX_VERTICES_PER_INSTANCE,
                  (uint32_t) vertexBytes / TERMUX_INSTANCE_BYTES, 0, 0);
    }
    vkCmdEndRenderPass(commandBuffer);
    if (!vk_succeeded(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer")) return -1;
    VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo submit = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &frame->imageAvailable,
        .pWaitDstStageMask = &waitStage,
        .commandBufferCount = 1,
        .pCommandBuffers = &commandBuffer,
        .signalSemaphoreCount = 1,
        .pSignalSemaphores = &frame->renderFinished,
    };
    /* Reset only when a submission is ready. Any earlier atlas/recording failure leaves the
     * previous signaled fence intact, so teardown and bounded renderer recovery cannot deadlock. */
    if (!vk_succeeded(vkResetFences(renderer->device, 1, &frame->fence),
                      "vkResetFences")) return -1;
    if (!vk_succeeded(vkQueueSubmit(renderer->queue, 1, &submit, frame->fence),
                      "vkQueueSubmit")) return -1;
    VkPresentInfoKHR present = {
        .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &frame->renderFinished,
        .swapchainCount = 1,
        .pSwapchains = &renderer->swapchain,
        .pImageIndices = &imageIndex,
    };
    VkResult presentResult = vkQueuePresentKHR(renderer->queue, &present);
    renderer->renderedFrames++;
    renderer->currentFrame = (renderer->currentFrame + 1) % TERMUX_FRAMES_IN_FLIGHT;
    if (presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR ||
        acquire == VK_SUBOPTIMAL_KHR) {
        renderer->retryCount++;
        if (!force_recreate_swapchain(renderer, width, height)) return -1;
        return presentResult == VK_ERROR_OUT_OF_DATE_KHR ? 0 : 1;
    }
    if (presentResult != VK_SUCCESS) {
        LOGE("vkQueuePresentKHR failed=%d", (int) presentResult);
        return -1;
    }
    return 1;
}

JNIEXPORT jlong JNICALL
Java_com_termux_view_TerminalVulkanRenderer_nativeCreate(JNIEnv *env, jclass clazz,
                                                          jobject surface, jint width,
                                                          jint height) {
    (void) clazz;
    if (surface == NULL || width <= 0 || height <= 0) return 0;
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (window == NULL) {
        LOGE("ANativeWindow_fromSurface returned null");
        return 0;
    }
    TermuxRenderer *renderer = calloc(1, sizeof(*renderer));
    if (renderer == NULL) {
        ANativeWindow_release(window);
        return 0;
    }
    if (!create_renderer(renderer, window, (uint32_t) width, (uint32_t) height)) {
        LOGE("renderer initialization failed");
        destroy_renderer(renderer);
        free(renderer);
        return 0;
    }
    return (jlong) (intptr_t) renderer;
}

JNIEXPORT void JNICALL
Java_com_termux_view_TerminalVulkanRenderer_nativeDestroy(JNIEnv *env, jclass clazz,
                                                           jlong handle) {
    (void) env;
    (void) clazz;
    TermuxRenderer *renderer = (TermuxRenderer *) (intptr_t) handle;
    if (renderer == NULL) return;
    destroy_renderer(renderer);
    free(renderer);
}

JNIEXPORT jint JNICALL
Java_com_termux_view_TerminalVulkanRenderer_nativeRender(
    JNIEnv *env, jclass clazz, jlong handle, jint width, jint height, jint backgroundColor,
    jfloat viewportYOffset, jobject vertices, jint vertexBytes, jlong vertexGeneration,
    jobject maskBitmap, jint maskGeneration,
    jint maskLeft, jint maskTop, jint maskRight, jint maskBottom, jobject colorBitmap,
    jint colorGeneration, jint colorLeft, jint colorTop, jint colorRight, jint colorBottom,
    jobject runMaskBitmap, jint runMaskGeneration,
    jint runMaskLeft, jint runMaskTop, jint runMaskRight, jint runMaskBottom) {
    (void) clazz;
    TermuxRenderer *renderer = (TermuxRenderer *) (intptr_t) handle;
    if (renderer == NULL || width <= 0 || height <= 0 || maskBitmap == NULL ||
        colorBitmap == NULL || runMaskBitmap == NULL) {
        return -1;
    }
    int result = render_frame(env, renderer, (uint32_t) width, (uint32_t) height,
                              backgroundColor, viewportYOffset, vertices, vertexBytes,
                              (uint64_t) vertexGeneration, maskBitmap,
                              maskGeneration, maskLeft, maskTop, maskRight, maskBottom,
                              colorBitmap, colorGeneration, colorLeft, colorTop,
                              colorRight, colorBottom, runMaskBitmap, runMaskGeneration,
                              runMaskLeft, runMaskTop, runMaskRight, runMaskBottom);
    if (renderer->renderedFrames != 0 && renderer->renderedFrames % 120u == 0u) {
        LOGI("frames=%llu uploads=%llu vertex=%llu/%llu retries=%llu swapchain=%llu",
             (unsigned long long) renderer->renderedFrames,
             (unsigned long long) renderer->uploadedRegions,
             (unsigned long long) renderer->vertexUploads,
             (unsigned long long) renderer->vertexUploadReuses,
             (unsigned long long) renderer->retryCount,
             (unsigned long long) renderer->swapchainRecreates);
    }
    return result;
}
